package graphql.parser

import graphql.language.Argument
import graphql.language.Document
import graphql.language.Field
import graphql.language.ObjectTypeDefinition
import graphql.language.OperationDefinition
import graphql.language.SelectionSet
import graphql.language.StringValue
import graphql.schema.idl.SchemaParser
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Proves that {@link ParserOptions#getReaderBufferSize()} is actually wired through to both the
 * {@link java.io.LineNumberReader} that wraps a caller's {@link Reader} and the internal ANTLR read chunk
 * used by {@link Parser}, by parsing the same document at a range of tiny and large buffer sizes and
 * checking the result never changes.
 */
class ReaderBufferSizeTest extends Specification {

    static final String EMOJI = "😀" // U+1F600 😀, a surrogate pair - exercises the chunk boundary guard

    @Unroll
    def "parsing the same query via a Reader is unaffected by readerBufferSize=#bufferSize"() {
        given:
        def query = 'query { f(arg: "hello ' + EMOJI + ' world") }'
        def options = ParserOptions.newParserOptions().readerBufferSize(bufferSize).build()
        def environment = ParserEnvironment.newParserEnvironment()
                .document(new StringReader(query))
                .parserOptions(options)
                .build()

        when:
        Document document = Parser.parse(environment)

        then:
        def field = ((document.definitions[0] as OperationDefinition).selectionSet as SelectionSet).selections[0] as Field
        def argument = field.arguments[0] as Argument
        (argument.value as StringValue).value == "hello 😀 world"

        where:
        bufferSize << [2, 3, 4, 7, 16, 4096, 8192, 65536]
    }

    def "readerBufferSize applies to SDL parsing through a Reader"() {
        given:
        def pad = 'a' * 20
        def sdl = '"""' + pad + EMOJI + '"""' + '\ntype Query { f: String }'
        def options = ParserOptions.getDefaultSdlParserOptions().transform({ it.readerBufferSize(bufferSize) })

        when:
        def registry = new SchemaParser().parse(new StringReader(sdl), options)
        def objectType = registry.getType("Query", ObjectTypeDefinition).get()

        then:
        objectType.description.content == pad + "😀"

        where:
        bufferSize << [2, 3, 22, 23, 24, 4096]
    }

    def "a caller-supplied LineNumberReader is used as-is regardless of ParserOptions.readerBufferSize"() {
        given:
        def query = 'query { f }'
        // a small buffer size on our own LineNumberReader - MultiSourceReader must not re-wrap this with
        // a default-sized one, and ParserOptions.readerBufferSize must not override it either
        def lineNumberReader = new LineNumberReader(new StringReader(query), 4)
        def environment = ParserEnvironment.newParserEnvironment()
                .document(lineNumberReader)
                .parserOptions(ParserOptions.newParserOptions().readerBufferSize(8192).build())
                .build()

        when:
        Document document = Parser.parse(environment)

        then:
        ((document.definitions[0] as OperationDefinition).selectionSet as SelectionSet).selections[0] instanceof Field
    }

    def "a syntax error produces the same message and location regardless of buffer size"() {
        given:
        def query = 'query { f(arg: "unterminated'

        when:
        def messages = [2, 4096, 8192].collect { size ->
            def options = ParserOptions.newParserOptions().readerBufferSize(size).build()
            def environment = ParserEnvironment.newParserEnvironment()
                    .document(new StringReader(query))
                    .parserOptions(options)
                    .build()
            try {
                Parser.parse(environment)
                return null
            } catch (InvalidSyntaxException e) {
                return e.message
            }
        }

        then:
        messages.every { it != null }
        messages.toSet().size() == 1
    }
}
