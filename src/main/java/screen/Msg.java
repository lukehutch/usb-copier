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
package screen;

import aobtk.i18n.Str;

public class Msg {
    
    static final Str COPY = new Str("Copy", "복사", "复制");
    static final Str VIEW = new Str("View", "보기", "查看");
    static final Str WIPE = new Str("Wipe", "지우기", "擦拭");


    static final Str WIPE_METHOD = new Str("Wipe Method:", "지우는 방법:", "擦拭方法");
    static final Str QUICK = new Str("Quick", "빨리", "快速地");
    static final Str SECURE = new Str("Secure", "안전하게", "安全地");
    static final Str ERASE_ALL_WARNING = new Str("ERASE ALL?", "모두 다 지울까?", "擦拭一切吗?");

    static final Str NEED_2_DRIVES = new Str("Insert another\nUSB drive", "USB 드라이브를\n하나 더 넣으십시오", "插入另一个USB");

    static final Str FREE = new Str("Free", "여유", "可用空间");
    static final Str WIPEQ = new Str("Wipe?", "지울까?", "擦拭吗?");

    static final Str ERROR = new Str("Error", "오류", "发生错误");
    static final Str CANCELED = new Str("Canceled", "취소 되었다", "操作被取消了");
    static final Str COMPLETED = new Str("Completed", "성공적 완료", "成功完成");

    private static final Str SRC = new Str("Src", "원본", "复制源", "源驱动器");
    static final Str DEST = new Str("Dest", "대상", "目标驱动器");

    static final Str READING = new Str("Reading #$0", "#$0를 읽고있다", "从#$0读取");
    static final Str EMPTY = new Str("#$0 is empty", "#$0는 비다", "#$0驱动器是空的");
    
    static final Str COPY_FROM = new Str("Copy from #$0", "#$0에서 복사 시작", "从#$0读取复制");

    static final Str ERASING = new Str("Erasing #$0", "#$0를 지우는 중", "正在擦除驱动器#$0");
    static final Str NO_DRIVES = new Str("Insert USB Drive", "USB를 넣으십시오", "请插入USB驱动器");

    static final Str NUM_FILES = new Str("#$0: $1 files", "#$0: 파일 $1개", "#$0驱动器包含$1个文件");
    static final Str CANT_READ_PORT = new Str("Can't read #$0", "#$0를 읽을 수 없다", "无法读取驱动器#$0");
}
