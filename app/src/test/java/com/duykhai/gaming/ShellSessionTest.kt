package com.duykhai.gaming

import org.junit.Assert.*
import org.junit.Test

class ShellSessionTest {
    @Test fun shellReusesSameProcessAcrossCommands() {
        val shell = ShellSession(listOf("sh"))
        try {
            val first = shell.execute("echo $$")
            val second = shell.execute("echo $$")
            assertEquals("0", first[0])
            assertEquals("0", second[0])
            assertEquals(first[1], second[1])
            assertTrue(shell.isAlive())
            val output = shell.execute("printf 'hello world\n'")
            assertEquals("0", output[0])
            assertEquals("hello world", output[1])
        } finally { shell.close() }
    }

    @Test fun exitCodeIsNotConfusedWithOutput() {
        val shell = ShellSession(listOf("sh"))
        try {
            val first = shell.execute("echo sample; false")
            assertEquals("1", first[0])
            assertTrue(first[1].contains("sample"))
            val next = shell.execute("echo still-alive")
            assertEquals("0", next[0])
            assertEquals("still-alive", next[1])
        } finally { shell.close() }
    }
}
