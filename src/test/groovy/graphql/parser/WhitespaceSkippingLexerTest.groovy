package graphql.parser

import graphql.parser.antlr.GraphqlLexer
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.Token
import spock.lang.Specification

import java.util.function.BiConsumer

class WhitespaceSkippingLexerTest extends Specification {

    private static List<Token> allTokens(GraphqlLexer lexer) {
        def tokens = []
        def token = lexer.nextToken()
        while (token.getType() != Token.EOF) {
            tokens.add(token)
            token = lexer.nextToken()
        }
        tokens
    }

    private static String document = "\ufeffquery foo {\r\n\ta,\u2028b # comment\n}"

    def "emits the same tokens as the standard lexer apart from whitespace"() {
        given:
        def standardTokens = allTokens(new GraphqlLexer(CharStreams.fromString(document)))
        def skippingLexer = new WhitespaceSkippingLexer(CharStreams.fromString(document), Integer.MAX_VALUE, { max, token -> })

        when:
        def tokens = allTokens(skippingLexer)

        then:
        standardTokens.count { it.getChannel() == Parser.CHANNEL_WHITESPACE } == 10
        tokens.collect { it.toString() } == standardTokens.findAll { it.getChannel() != Parser.CHANNEL_WHITESPACE }.collect { it.toString() }
        tokens.collect { it.getText() } == ["query", "foo", "{", "a", "b", "# comment", "}"]
        tokens.find { it.getChannel() == Parser.CHANNEL_COMMENTS }.getText() == "# comment"
    }

    def "calls back with the first whitespace token over the limit"() {
        given:
        Token offendingToken = null
        BiConsumer<Integer, Token> onTooManyTokens = { max, token ->
            offendingToken = token
            throw new IllegalStateException("stop at $max")
        }
        def lexer = new WhitespaceSkippingLexer(CharStreams.fromString(document), 4, onTooManyTokens)

        when:
        allTokens(lexer)

        then:
        def e = thrown(IllegalStateException)
        e.message == "stop at 4"
        offendingToken.getType() == GraphqlLexer.LF
        offendingToken.getChannel() == Parser.CHANNEL_WHITESPACE
        offendingToken.getText() == "\n"
        offendingToken.getLine() == 1
        offendingToken.getCharPositionInLine() == 13
    }

    def "does not call back when the whitespace is exactly at the limit"() {
        given:
        def calledBack = false
        def lexer = new WhitespaceSkippingLexer(CharStreams.fromString(document), 10, { max, token -> calledBack = true })

        when:
        def tokens = allTokens(lexer)

        then:
        !calledBack
        tokens.size() == 7
    }
}
