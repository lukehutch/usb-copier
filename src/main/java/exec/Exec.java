package exec;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class Exec {

    public static String[] prependCommand = null; // e.g. { "sudo", "-u", "pi" };

    /** Start new threads in daemon mode so they are killed when JVM tries to shut down. */
    public static ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    public static void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
        executor.shutdownNow();
    }

    // -------------------------------------------------------------------------------------------------------------

    private static String readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString(StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------------------------------------------

    @FunctionalInterface
    public static interface ConsumerThrowingIOException<T> {
        public void accept(T val) throws IOException;
    }

    public static Future<Integer> exec(ConsumerThrowingIOException<InputStream> stdoutConsumer,
            ConsumerThrowingIOException<InputStream> stderrConsumer, String... cmdAndArgs) {
        String[] cmd;
        if (prependCommand != null && prependCommand.length > 0) {
            // Prepend "sudo -u pi" or similar
            cmd = Arrays.copyOf(prependCommand, prependCommand.length + cmdAndArgs.length);
            System.arraycopy(cmdAndArgs, 0, cmd, prependCommand.length, cmdAndArgs.length);
        } else {
            cmd = cmdAndArgs;
        }

        Future<Process> processFuture = executor.submit(() -> Runtime.getRuntime().exec(cmd));

        AtomicReference<Future<Void>> stdoutProcessorFuture = //
                new AtomicReference<>();
        AtomicReference<Future<Void>> stderrProcessorFuture = //
                new AtomicReference<>();
        AtomicReference<Future<Integer>> exitCodeFuture = //
                new AtomicReference<>();

        Runnable cancel = () -> {
            try {
                processFuture.get().destroy();
            } catch (Exception e) {
                // Ignore (exitCodeFuture.get() will still detect exceptions)
            }
            // Standard Java I/O can't be interrupted, but interrupt output
            // processors anyway, in case they are blocking on something that
            // is interruptible. Their InputStream will be closed by
            // processFuture.get().destroy().
            if (stdoutProcessorFuture.get() != null) {
                stdoutProcessorFuture.get().cancel(true);
            }
            if (stderrProcessorFuture.get() != null) {
                stderrProcessorFuture.get().cancel(true);
            }
            if (exitCodeFuture.get() != null) {
                stderrProcessorFuture.get().cancel(true);
            }
        };

        if (stdoutConsumer != null) {
            stdoutProcessorFuture.set(executor.submit(() -> {
                try {
                    InputStream inputStream = processFuture.get().getInputStream();
                    if (inputStream != null) {
                        // inputStream has not been redirected
                        stdoutConsumer.accept(inputStream);
                    }
                    return null;
                } catch (Exception e) {
                    cancel.run();
                    throw e;
                }
            }));
        }

        if (stderrConsumer != null) {
            stderrProcessorFuture.set(executor.submit(() -> {
                try {
                    InputStream errorStream = processFuture.get().getErrorStream();
                    if (errorStream != null) {
                        // errorStream has not been redirected
                        stderrConsumer.accept(errorStream);
                    }
                    return null;
                } catch (Exception e) {
                    cancel.run();
                    throw e;
                }
            }));
        }

        exitCodeFuture.set(executor.submit(() -> {
            try {
                return processFuture.get().waitFor();
            } catch (Exception e) {
                cancel.run();
                throw e;
            }
        }));

        // Async completion barrier -- wait for process to exit, and for output
        // processors to complete
        return executor.submit(() -> {
            Exception exception = null;
            int exitCode = 1;
            try {
                exitCode = exitCodeFuture.get().get();
            } catch (InterruptedException | CancellationException | ExecutionException e) {
                cancel.run();
                exception = e;
            }
            if (stderrProcessorFuture != null) {
                try {
                    stderrProcessorFuture.get().get();
                } catch (InterruptedException | CancellationException | ExecutionException e) {
                    cancel.run();
                    if (exception == null) {
                        exception = e;
                    } else if (e instanceof ExecutionException) {
                        exception.addSuppressed(e);
                    }
                }
            }
            if (stdoutProcessorFuture != null) {
                try {
                    stderrProcessorFuture.get().get();
                } catch (InterruptedException | CancellationException | ExecutionException e) {
                    cancel.run();
                    if (exception == null) {
                        exception = e;
                    } else if (e instanceof ExecutionException) {
                        exception.addSuppressed(e);
                    }
                }
            }
            if (exception != null) {
                throw exception;
            } else {
                return exitCode;
            }
        });
    }

    public static Future<Integer> exec(ConsumerThrowingIOException<InputStream> stdoutConsumer,
            String... cmdAndArgs) {
        return exec(stdoutConsumer, null, cmdAndArgs);
    }

    public static Future<Integer> exec(String... cmdAndArgs) {
        return exec(null, null, cmdAndArgs);
    }

    // -------------------------------------------------------------------------------------------------------------

    public static Future<Integer> execConsumingLines(Consumer<String> stdinLineConsumer,
            Consumer<String> stderrLineConsumer, String... cmdAndArgs) {
        return exec(
                stdinLineConsumer == null ? null
                        : stdinStream -> new BufferedReader(new InputStreamReader(stdinStream)).lines()
                                .forEach(stdinLineConsumer),
                stderrLineConsumer == null ? null
                        : stderrStream -> new BufferedReader(new InputStreamReader(stderrStream)).lines()
                                .forEach(stderrLineConsumer),
                cmdAndArgs);
    }

    public static Future<Integer> execConsumingLines(Consumer<String> stdinLineConsumer, String... cmdAndArgs) {
        return execConsumingLines(stdinLineConsumer, null, cmdAndArgs);
    }

    // -------------------------------------------------------------------------------------------------------------

    public static Future<Integer> execConsumingOutput(Consumer<String> stdoutConsumer,
            Consumer<String> stderrConsumer, String... cmdAndArgs) {
        return exec(stdoutConsumer == null ? null : stdinStream -> stdoutConsumer.accept(readAll(stdinStream)),
                stderrConsumer == null ? null : stderrStream -> stderrConsumer.accept(readAll(stderrStream)),
                cmdAndArgs);
    }

    public static Future<Integer> execConsumingOutput(Consumer<String> stdoutConsumer, String... cmdAndArgs) {
        return execConsumingOutput(stdoutConsumer, null, cmdAndArgs);
    }

    // -------------------------------------------------------------------------------------------------------------

    public static class TaskOutput {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        private TaskOutput(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    public static Future<TaskOutput> execWithTaskOutput(String... cmdAndArgs) {
        return executor.submit(() -> {
            AtomicReference<String> stdout = new AtomicReference<>();
            AtomicReference<String> stderr = new AtomicReference<>();
            try {
                int exitCode = execConsumingOutput(stdout::set, stderr::set, cmdAndArgs).get();
                return new TaskOutput(exitCode, stdout.get(), stderr.get());
            } catch (InterruptedException | CancellationException | ExecutionException e) {
                String out = stdout.get();
                if (out == null) {
                    out = "";
                }
                String err = stderr.get();
                if (err == null) {
                    err = e.toString();
                } else {
                    err = e + " : " + err;
                }
                return new TaskOutput(1, out, err);
            }
        });
    }

    /**
     * Get the {@link TaskOutput} from a {@code Future<TaskOutput>}, wrapping any exceptions thrown in a valid
     * {@link TaskOutput} instance with exitCode 1, so that exceptions do not have to be caught.
     */
    public static TaskOutput getTaskOutputSafeSynchronous(Future<TaskOutput> task) {
        try {
            return task.get();
        } catch (ExecutionException e) {
            // Should not happen, since execWithTaskOutput() already catches this
            return new TaskOutput(1, "", "Task threw unexpected exception: " + e);
        } catch (InterruptedException | CancellationException e) {
            // Should only happen in case of race condition, since execWithTaskOutput() already catches this
            return new TaskOutput(1, "", "Task interrupted or canceled");
        }
    }

    public static TaskOutput execWithTaskOutputSynchronous(String... cmdAndArgs) {
        return getTaskOutputSafeSynchronous(execWithTaskOutput(cmdAndArgs));
    }

    // -------------------------------------------------------------------------------------------------------------

    public static <T> void then(Future<T> task, Consumer<T> onSuccess, Consumer<Exception> onFailure) {
        executor.execute(() -> {
            try {
                T taskResult = task.get();
                if (onSuccess != null) {
                    onSuccess.accept(taskResult);
                }
            } catch (InterruptedException | CancellationException e) {
                // Don't call onFailure if there was a cancellation
            } catch (ExecutionException e) {
                if (onFailure != null) {
                    onFailure.accept(e);
                }
            }
        });
    }

    public static void then(Future<TaskOutput> task, Consumer<TaskOutput> next) {
        executor.execute(() -> {
            next.accept(getTaskOutputSafeSynchronous(task));
        });
    }

    @FunctionalInterface
    public interface TaskOutputMapper<T> {
        public T map(TaskOutput taskOutput) throws Exception;
    }

    public static <V> Future<V> thenMap(Future<TaskOutput> task, TaskOutputMapper<V> next) {
        return executor.submit(() -> {
            return next.map(getTaskOutputSafeSynchronous(task));
        });
    }

    @SafeVarargs
    public static <T> Future<T> barrier(Future<T>... tasks) {
        return executor.submit(() -> {
            T lastValue = null;
            for (Future<T> task : tasks) {
                try {
                    lastValue = task.get();
                } catch (Exception e) {
                    for (Future<T> t : tasks) {
                        t.cancel(true);
                    }
                    throw e;
                }
            }
            return lastValue;
        });
    }

    @SafeVarargs
    public static <T> Future<T> barrierIgnoringFailure(Future<T>... tasks) {
        return executor.submit(() -> {
            T lastValue = null;
            for (Future<T> task : tasks) {
                try {
                    lastValue = task.get();
                } catch (Exception e) {
                    // Ignore
                }
            }
            return lastValue;
        });
    }
}
