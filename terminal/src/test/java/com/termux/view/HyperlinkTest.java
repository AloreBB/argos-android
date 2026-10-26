package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class HyperlinkTest {

    private static TerminalBuffer screenAfter(String output) {
        TerminalOutput sink = new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) {}
            @Override public void titleChanged(String oldTitle, String newTitle) {}
            @Override public void onCopyTextToClipboard(String text) {}
            @Override public void onPasteTextFromClipboard() {}
            @Override public void onBell() {}
            @Override public void onColorsChanged() {}
        };
        TerminalEmulator emulator = new TerminalEmulator(sink, 40, 5, 10, 20, 100, null);
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
        return emulator.getScreen();
    }

    @Test
    public void osc8LinksOnlyTheirText() {
        // What Claude Code prints for a branch/PR: the URL is hidden behind the label.
        TerminalBuffer screen = screenAfter("PR \033]8;;https://github.com/o/r/pull/7\033\\#7\033]8;;\033\\ ok");
        assertNull(screen.getLinkAt(0, 2));
        assertEquals("https://github.com/o/r/pull/7", screen.getLinkAt(0, 3));
        assertEquals("https://github.com/o/r/pull/7", screen.getLinkAt(0, 4));
        assertNull(screen.getLinkAt(0, 6));
    }

    @Test
    public void overwritingACellDropsItsLink() {
        TerminalBuffer screen = screenAfter("\033]8;;https://a.io\007link\033]8;;\007\rX");
        assertNull(screen.getLinkAt(0, 0));
        assertEquals("https://a.io", screen.getLinkAt(0, 1));
    }

    @Test
    public void plainUrlInWord() {
        assertEquals("https://news.ycombinator.com/item?id=1", TerminalView.urlInWord("(https://news.ycombinator.com/item?id=1)."));
        assertEquals("http://x.io/a", TerminalView.urlInWord("ver:http://x.io/a"));
        assertNull(TerminalView.urlInWord("feature/branch"));
        assertNull(TerminalView.urlInWord("https://"));
    }
}
