/*
 * This file is part of the Adafruit OLED Bonnet Toolkit: a Java toolkit for the Adafruit 128x64 OLED bonnet,
 * with support for the screen, D-pad/buttons, UI layout, and task scheduling.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/Adafruit-OLED-Bonnet-Toolkit
 * 
 * This code is not associated with or endorsed by Adafruit. Adafruit is a trademark of Limor "Ladyada" Fried. 
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package i18n;

import aobtk.i18n.Str;

public class Msg {

    public static final Str COPY = new Str("Copy", "복사", "复制");
    public static final Str VIEW = new Str("View", "보기", "查看");
    public static final Str WIPE = new Str("Wipe", "지우기", "擦拭");

    public static final Str WIPE_METHOD = new Str("Wipe Method:", "지우는 방법:", "擦拭方法");
    public static final Str QUICK = new Str("Quick", "빨리", "快速地");
    public static final Str SECURE = new Str("Secure", "안전하게", "安全地");
    public static final Str ERASE_ALL_WARNING = new Str("ERASE ALL?", "모두 다 지울까?", "擦拭一切吗?");

    public static final Str NEED_2_DRIVES = new Str("Insert another\nUSB drive", "USB 드라이브를\n하나 더 넣으십시오",
            "插入另一个USB");

    public static final Str FREE = new Str("Free", "여유", "空间");
    public static final Str NEEDED = new Str("Short", "부족", "不足");

    public static final Str ERROR = new Str("Error", "오류", "发生错误");
    public static final Str CANCELED = new Str("Canceled", "취소 되었다", "取消了");
    public static final Str COMPLETED = new Str("Completed", "성공적 완료", "成功完成");

    public static final Str SRC = new Str("Src", "원본", "复制源"); // Currently unused
    public static final Str DEST = new Str("Dest", "대상", "目标");

    public static final Str READING = new Str("Reading #$0", "#$0 를 읽고있다", "从 #$0 读取");
    public static final Str EMPTY = new Str("#$0 is empty", "#$0 는 비다", "#$0 是空的");

    public static final Str START_COPYING = new Str("Copy from #$0", "#$0 에서 복사 시작", "从 #$0 开始复制");
    public static final Str COPYING = new Str("Copying from #$0", "#$0 에서 복사중", "从 #$0 复制中");

    public static final Str ERASING = new Str("Erasing #$0", "#$0 를 지우는 중", "正在擦除 #$0");
    public static final Str NO_DRIVES = new Str("Insert USB Drive", "USB를 넣으십시오", "请插入 USB");

    public static final Str NUM_FILES = new Str("#$0: $1 files", "#$0: 파일 $1 개", "#$0: $1 个文件");
    public static final Str FILE_COUNT = new Str("$0 files", "파일 $0 개", "$0 个文件");
    public static final Str CANT_READ_PORT = new Str("Can't read #$0", "#$0 를 읽을 수 없다", "无法读取 #$0");
}
