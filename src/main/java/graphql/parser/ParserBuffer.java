package graphql.parser;

import graphql.ExperimentalApi;
import org.antlr.v4.runtime.CodePointBuffer;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.IntStream;
import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A parser buffer holds the characters of a document while the {@link Parser} lexes and parses it, and can be
 * reused from one parse to the next by passing it to {@link ParserEnvironment.Builder#parserBuffer(ParserBuffer)}.
 * <p>
 * Without one, each parse allocates arrays sized to the document it reads. When parsing many large documents one after
 * another, such as multi-megabyte SDL, a reused buffer keeps its arrays and only grows them when a document is larger
 * than any it has read before.
 * <p>
 * A buffer can be used by one parse at a time. A parse given a buffer that another parse is using throws an
 * {@link IllegalStateException}. One parse after another, the buffer can be used from any thread, for example from a
 * pool or a {@link ThreadLocal}.
 * <p>
 * A buffer retains arrays as large as the largest document it has read, between one and seven bytes per character
 * depending on the characters it has seen, until it is garbage collected. To release them, stop referencing the buffer
 * and create a new one.
 * <p>
 * The {@link graphql.language.Document} returned by a parse does not reference the buffer. The ANTLR tokens and rule
 * contexts created by a parse do, such as those captured by {@link NodeToRuleCapturingParser} or the
 * {@link ParsingListener.Token}s passed to a {@link ParsingListener}, and are only valid until the buffer's next parse.
 */
@ExperimentalApi
@NullMarked
public class ParserBuffer {

    // matches the chunk size ANTLR's CharStreams.fromReader() uses internally
    private static final int CHUNK_SIZE = 4096;

    private final AtomicBoolean inUse = new AtomicBoolean();
    private final char[] chunk = new char[CHUNK_SIZE];
    private byte[] bytes = new byte[0];
    private char[] chars = new char[0];
    private int[] ints = new int[0];
    private CodePointBuffer.Type type = CodePointBuffer.Type.BYTE;
    private int size;

    private ParserBuffer() {
    }

    /**
     * @return a new, empty parser buffer
     */
    public static ParserBuffer newParserBuffer() {
        return new ParserBuffer();
    }

    void acquire() {
        if (!inUse.compareAndSet(false, true)) {
            throw new IllegalStateException("This ParserBuffer is already in use by another parse");
        }
    }

    void release() {
        inUse.set(false);
    }

    CodePointCharStream read(Reader reader) throws IOException {
        type = CodePointBuffer.Type.BYTE;
        size = 0;
        try {
            int held = 0;
            int read;
            while ((read = reader.read(chunk, held, CHUNK_SIZE - held)) != -1) {
                held = appendHoldingBackHighSurrogate(held + read);
            }
            append(held);
        } finally {
            reader.close();
        }
        return CodePointCharStream.fromBuffer(codePointBuffer(), IntStream.UNKNOWN_SOURCE_NAME);
    }

    // a trailing high surrogate is carried into the next chunk so that a pair split across two reads is decoded as one code point
    private int appendHoldingBackHighSurrogate(int length) {
        if (length == 0 || !Character.isHighSurrogate(chunk[length - 1])) {
            append(length);
            return 0;
        }
        append(length - 1);
        chunk[0] = chunk[length - 1];
        return 1;
    }

    private CodePointBuffer codePointBuffer() {
        switch (type) {
            case BYTE:
                return CodePointBuffer.withBytes(ByteBuffer.wrap(bytes, 0, size));
            case CHAR:
                return CodePointBuffer.withChars(CharBuffer.wrap(chars, 0, size));
            default:
                return CodePointBuffer.withInts(IntBuffer.wrap(ints, 0, size));
        }
    }

    // like ANTLR's CodePointBuffer.Builder: bytes while all chars are Latin-1, then chars, then ints from the first high surrogate
    private void append(int length) {
        int index = 0;
        if (type == CodePointBuffer.Type.BYTE) {
            index = appendBytes(length);
            if (index == length) {
                return;
            }
            upgradeFromBytes(chunk[index], length - index);
        }
        if (type == CodePointBuffer.Type.CHAR) {
            index = appendChars(index, length);
            if (index == length) {
                return;
            }
            upgradeCharsToInts(length - index);
        }
        appendInts(index, length);
    }

    private int appendBytes(int length) {
        ensureBytesCapacity(size + length);
        byte[] out = bytes;
        int outIndex = size;
        int index = 0;
        while (index < length && chunk[index] <= 0xFF) {
            out[outIndex++] = (byte) chunk[index++];
        }
        size = outIndex;
        return index;
    }

    private int appendChars(int index, int length) {
        ensureCharsCapacity(size + length - index);
        char[] out = chars;
        int outIndex = size;
        while (index < length && !Character.isHighSurrogate(chunk[index])) {
            out[outIndex++] = chunk[index++];
        }
        size = outIndex;
        return index;
    }

    private void appendInts(int index, int length) {
        ensureIntsCapacity(size + length - index);
        int[] out = ints;
        int outIndex = size;
        while (index < length) {
            char c = chunk[index++];
            if (Character.isHighSurrogate(c) && index < length && Character.isLowSurrogate(chunk[index])) {
                out[outIndex++] = Character.toCodePoint(c, chunk[index++]);
            } else {
                out[outIndex++] = c;
            }
        }
        size = outIndex;
    }

    private void upgradeFromBytes(char next, int remaining) {
        if (Character.isHighSurrogate(next)) {
            upgradeBytesToInts(remaining);
            return;
        }
        upgradeBytesToChars(remaining);
    }

    private void upgradeBytesToChars(int remaining) {
        chars = newCapacity(chars, size + remaining);
        for (int i = 0; i < size; i++) {
            chars[i] = (char) (bytes[i] & 0xFF);
        }
        type = CodePointBuffer.Type.CHAR;
    }

    private void upgradeBytesToInts(int remaining) {
        ints = newCapacity(ints, size + remaining);
        for (int i = 0; i < size; i++) {
            ints[i] = bytes[i] & 0xFF;
        }
        type = CodePointBuffer.Type.INT;
    }

    private void upgradeCharsToInts(int remaining) {
        ints = newCapacity(ints, size + remaining);
        for (int i = 0; i < size; i++) {
            ints[i] = chars[i];
        }
        type = CodePointBuffer.Type.INT;
    }

    private void ensureBytesCapacity(int needed) {
        if (bytes.length < needed) {
            bytes = Arrays.copyOf(bytes, grownCapacity(bytes.length, needed));
        }
    }

    private void ensureCharsCapacity(int needed) {
        if (chars.length < needed) {
            chars = Arrays.copyOf(chars, grownCapacity(chars.length, needed));
        }
    }

    private void ensureIntsCapacity(int needed) {
        if (ints.length < needed) {
            ints = Arrays.copyOf(ints, grownCapacity(ints.length, needed));
        }
    }

    private static char[] newCapacity(char[] array, int needed) {
        return array.length >= needed ? array : new char[grownCapacity(array.length, needed)];
    }

    private static int[] newCapacity(int[] array, int needed) {
        return array.length >= needed ? array : new int[grownCapacity(array.length, needed)];
    }

    private static int grownCapacity(int capacity, int needed) {
        // capacity * 2 overflows to a negative number past 1G, leaving needed
        return Math.max(needed, capacity * 2);
    }
}
