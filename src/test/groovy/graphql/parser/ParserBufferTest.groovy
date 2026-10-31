package graphql.parser

import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.Field
import graphql.language.Node
import graphql.language.OperationDefinition
import graphql.language.StringValue
import graphql.parser.exceptions.ParseCancelledTooManyCharsException
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CodePointCharStream
import org.antlr.v4.runtime.misc.Interval
import spock.lang.Specification
import spock.lang.Unroll

class ParserBufferTest extends Specification {

    static final String EMOJI = "😀"

    static final ParserOptions UNLIMITED_OPTIONS = ParserOptions.getDefaultSdlParserOptions().transform {
        it.maxTokens(Integer.MAX_VALUE).maxCharacters(Integer.MAX_VALUE).maxWhitespaceTokens(Integer.MAX_VALUE)
    }

    static String resource(String name) {
        ParserBufferTest.class.getResource("/" + name).getText("UTF-8")
    }

    static List<Integer> codePoints(CodePointCharStream stream) {
        (0..<stream.size()).collect { stream.LA(it + 1) }
    }

    static Class<?> storage(CodePointCharStream stream) {
        stream.getInternalStorage().getClass()
    }

    static CodePointCharStream read(ParserBuffer buffer, Reader reader) {
        buffer.acquire()
        try {
            return buffer.read(reader)
        } finally {
            buffer.release()
        }
    }

    static void assertSameAsAntlr(CodePointCharStream actual, String text) {
        def expected = CharStreams.fromString(text)
        assert actual.size() == expected.size()
        assert codePoints(actual) == codePoints(expected)
        assert storage(actual) == storage(expected)
        if (text.length() > 0) {
            assert actual.getText(Interval.of(0, actual.size() - 1)) == text
        }
    }

    /**
     * a reader that returns at most maxRead chars per read, so chunks end at many different places
     */
    static Reader trickling(String text, int maxRead) {
        new FilterReader(new StringReader(text)) {
            @Override
            int read(char[] cbuf, int off, int len) throws IOException {
                return super.read(cbuf, off, Math.min(len, maxRead))
            }
        }
    }

    @Unroll
    def "reads #description into the same code points as ANTLR"() {
        when:
        def stream = read(ParserBuffer.newParserBuffer(), new StringReader(text))

        then:
        assertSameAsAntlr(stream, text)

        where:
        description                                  | text
        "an empty document"                          | ""
        "ASCII"                                      | "query { hello }"
        "Latin-1"                                    | "café ÿ \u0080"
        "BMP beyond Latin-1"                         | "café 日本 Ā"
        "a surrogate pair"                           | "a" + EMOJI + "b"
        "surrogate pairs after BMP chars"            | "é日" + EMOJI + "日" + EMOJI
        "a lone high surrogate at the end"           | "abc\uD83D"
        "a lone high surrogate before a char"        | "a\uD83Db"
        "two high surrogates then a low one"         | "a\uD83D😀b"
        "a lone low surrogate"                       | "a\uDE00b"
        "a lone low surrogate then a pair"           | "a\uDE00b" + EMOJI
        "only a lone high surrogate"                 | "\uD83D"
        "upgrades in later chunks"                   | ("a" * 5000) + "é" + ("b" * 5000) + "日" + ("c" * 5000) + EMOJI + ("d" * 5000)
        "straight to ints in a later chunk"          | ("a" * 9000) + EMOJI + ("b" * 9000)
        "a high surrogate ending the last full read" | ("a" * 4095) + "\uD83D"
    }

    @Unroll
    def "a surrogate pair with its high surrogate at #index is read as one code point"() {
        given:
        def text = ("a" * index) + EMOJI + ("b" * 10)

        when:
        def stream = read(ParserBuffer.newParserBuffer(), new StringReader(text))

        then:
        assertSameAsAntlr(stream, text)

        where:
        index << (4090..4100) + (8185..8195) + (12280..12290)
    }

    @Unroll
    def "reads the same code points when the reader returns at most #maxRead chars at a time"() {
        given:
        def text = "café " + EMOJI + EMOJI + " 日 \uD83D x \uDE00 " + ("a" * 5000) + EMOJI + "\uD83D"

        when:
        def stream = read(ParserBuffer.newParserBuffer(), trickling(text, maxRead))

        then:
        assertSameAsAntlr(stream, text)

        where:
        maxRead << [1, 2, 3, 7, 4095]
    }

    def "a reused buffer reads documents of different sizes and char widths"() {
        given:
        def buffer = ParserBuffer.newParserBuffer()
        def texts = [
                ("日" * 10000) + EMOJI + ("a" * 10000),
                "small ascii",
                "",
                ("é" * 20000),
                "small " + EMOJI,
                ("a" * 30000) + "日",
                "é",
                ("a" * 40000),
                EMOJI,
        ]

        expect:
        texts.each { text ->
            assertSameAsAntlr(read(buffer, new StringReader(text)), text)
        }
    }

    def "the reader is closed after reading"() {
        given:
        def closed = false
        def reader = new StringReader("query { a }") {
            @Override
            void close() {
                closed = true
                super.close()
            }
        }

        when:
        read(ParserBuffer.newParserBuffer(), reader)

        then:
        closed
    }

    static ParserEnvironment environment(String document, ParserBuffer buffer) {
        def builder = ParserEnvironment.newParserEnvironment().document(document).parserOptions(UNLIMITED_OPTIONS)
        if (buffer != null) {
            builder.parserBuffer(buffer)
        }
        builder.build()
    }

    static List<String> sourceLocations(Node<?> node) {
        def location = node.sourceLocation
        def locations = [node.class.simpleName + "@" + location?.line + ":" + location?.column]
        node.children.each { locations.addAll(sourceLocations(it as Node<?>)) }
        locations
    }

    def "parsing a sequence of documents with one buffer gives the same documents as parsing without one"() {
        given:
        def buffer = ParserBuffer.newParserBuffer()
        def largeSchema2 = resource("large-schema-2.graphqls")
        def largeSchema3 = resource("large-schema-3.graphqls")
        def documents = [
                '"""日本 ' + EMOJI + '"""\n' + largeSchema3,
                "query { hello }",
                largeSchema2,
                '"café 日本"\ntype Query { f: String }',
                largeSchema3,
                'query { f(arg: "' + EMOJI + '") }',
                '# café\n' + largeSchema2,
                "{ a }",
        ]

        expect:
        documents.each { document ->
            Document withBuffer = Parser.parse(environment(document, buffer))
            Document withoutBuffer = Parser.parse(environment(document, null))
            assert AstPrinter.printAst(withBuffer) == AstPrinter.printAst(withoutBuffer)
            assert sourceLocations(withBuffer) == sourceLocations(withoutBuffer)
        }
    }

    def "a document parsed with a buffer is unaffected by the buffer's next parse"() {
        given:
        def buffer = ParserBuffer.newParserBuffer()

        when:
        Document first = Parser.parse(environment('query { f(arg: "first ' + EMOJI + '") }', buffer))
        Parser.parse(environment('query { g(arg: "second, and longer") }', buffer))

        then:
        def field = (first.definitions[0] as OperationDefinition).selectionSet.selections[0] as Field
        field.name == "f"
        (field.arguments[0].value as StringValue).value == "first " + EMOJI
    }

    def "a parse given a buffer in use throws IllegalStateException"() {
        given:
        def buffer = ParserBuffer.newParserBuffer()
        buffer.acquire()

        when:
        Parser.parse(environment("query { a }", buffer))

        then:
        thrown(IllegalStateException)

        when:
        buffer.release()
        def document = Parser.parse(environment("query { a }", buffer))

        then:
        AstPrinter.printAst(document) == AstPrinter.printAst(Parser.parse("query { a }"))
    }

    def "a parse with a buffer cannot start another parse with the same buffer"() {
        given:
        def buffer = ParserBuffer.newParserBuffer()
        ParsingListener listener = { token -> Parser.parse(environment("query { b }", buffer)) }
        def options = UNLIMITED_OPTIONS.transform { it.parsingListener(listener) }
        def reentrant = ParserEnvironment.newParserEnvironment()
                .document("query { a }")
                .parserOptions(options)
                .parserBuffer(buffer)
                .build()

        when:
        Parser.parse(reentrant)

        then:
        thrown(IllegalStateException)
    }

    def "the buffer is released after a parse fails with a syntax error"() {
        given:
        def buffer = ParserBuffer.newParserBuffer()

        when:
        Parser.parse(environment("query { a ", buffer))

        then:
        thrown(InvalidSyntaxException)

        when:
        def document = Parser.parse(environment("query { a }", buffer))

        then:
        AstPrinter.printAst(document) == AstPrinter.printAst(Parser.parse("query { a }"))
    }

    def "the buffer is released after a parse fails while reading the document"() {
        given:
        def buffer = ParserBuffer.newParserBuffer()
        def options = ParserOptions.newParserOptions().maxCharacters(10).build()
        def tooLong = ParserEnvironment.newParserEnvironment()
                .document("query { a b c d e f g }")
                .parserOptions(options)
                .parserBuffer(buffer)
                .build()

        when:
        Parser.parse(tooLong)

        then:
        thrown(ParseCancelledTooManyCharsException)

        when:
        def document = Parser.parse(environment("query { a }", buffer))

        then:
        AstPrinter.printAst(document) == AstPrinter.printAst(Parser.parse("query { a }"))
    }

    def "a parser environment has no buffer unless one is set"() {
        given:
        def buffer = ParserBuffer.newParserBuffer()

        expect:
        ParserEnvironment.newParserEnvironment().document("{ a }").build().getParserBuffer() == null
        ParserEnvironment.newParserEnvironment().document("{ a }").parserBuffer(buffer).build().getParserBuffer().is(buffer)
    }
}
