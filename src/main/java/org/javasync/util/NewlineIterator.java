/*
 * MIT License
 *
 * Copyright (c) 2019, Miguel Gamboa (gamboa.pt)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.javasync.util;

import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NewlineIterator extends Spliterators.AbstractSpliterator<String> {
    /*
     * Use {@code (?<=(...))} if you need to include the pattern characters in resulting match.
     * For instance {@code (?<=(\n))} includes the {@code \n} in resulting string line.
     */
    static final Pattern NEWLINE = Pattern.compile("\r?\n|\n");

    private int index = 0;
    final private CharSequence input;
    final private Matcher m;

    protected NewlineIterator(CharSequence input) {
        super(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.SIZED);
        this.input = input;
        this.m = NEWLINE.matcher(input);
    }
    @Override
    public boolean tryAdvance(Consumer<? super String> action) {
        while(m.find()) {
            if (index != 0 || index != m.start() || m.start() != m.end()) {
                action.accept(input.subSequence(index, m.start()).toString());
                index = m.end();
                return true;
            }
        }
        if(index < input.length()) {
            // Add remaining segment
            action.accept(input.subSequence(index, input.length()).toString());
            index = input.length();
            return true;
        } else {
            return false;
        }
    }

    /**
     * This method should be overridden whenever possible as stated in Java docs.
     * It peforms a bulk traversal in a single bulk computation.
     * @param action
     */
    @Override
    public void forEachRemaining(Consumer<? super String> action) {
        while(m.find()) {
            if (index != 0 || index != m.start() || m.start() != m.end()) {
                action.accept(input.subSequence(index, m.start()).toString());
                index = m.end();
            }
        }
        if(index < input.length()) {
            // Add remaining segment
            action.accept(input.subSequence(index, input.length()).toString());
            index = input.length();
        }
    }
}
