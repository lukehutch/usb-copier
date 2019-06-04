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
    
    static final Str COPY = new Str("Copy", "복사");
    static final Str VIEW = new Str("View", "보기");
    static final Str WIPE = new Str("Wipe", "지우기");


    static final Str WIPE_METHOD = new Str("Wipe Method:", "지우는 방법:");
    static final Str QUICK = new Str("Quick", "빨리");
    static final Str SECURE = new Str("Secure", "안전하게");
    static final Str ERASE_ALL_WARNING = new Str("ERASE ALL?", "모두 다 지울까?");

    static final Str NEED_2_DRIVES = new Str("Insert other USB", "USB 2개 필요하다");

    static final Str FREE = new Str("Free", "여유");
    static final Str WIPEQ = new Str("Wipe?", "지울까?");

    static final Str ERROR = new Str("Error", "오류");
    static final Str CANCELED = new Str("Canceled", "취소 되었다");
    static final Str COMPLETED = new Str("Completed", "성공적 완료");

    private static final Str WIPEQ0 = new Str("Wipe", "먼저");
    private static final Str WIPEQ1 = new Str("first?", "지울까?");

    private static final Str SRC = new Str("Src", "원본");
    static final Str DEST = new Str("Dest", "대상");

    static final Str READING = new Str("Reading #$0", "#$0를 읽고있다");
    static final Str EMPTY = new Str("#$0 is empty", "#$0는 비다");
    static final Str SRC_COUNT = new Str("Src:#$0, $1 files", " 원본:#$0," + "파일 $1개");
    
    static final Str COPY_FROM = new Str("Copy from #$0", "#$0에서 복사 시작");

    static final Str ERASING = new Str("Erasing #$0", "#$0를 지우는 중");
    static final Str NO_DRIVES = new Str("Insert USB Drive", "USB를 넣으십시오");

    static final Str NUM_FILES = new Str("#$0: $1 files", "#$0: 파일 $1개");
    static final Str CANT_READ_PORT = new Str("Can't read #$0", "#$0를 읽을 수 없다");
}
