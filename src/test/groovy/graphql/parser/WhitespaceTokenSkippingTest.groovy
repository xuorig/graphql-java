package graphql.parser

import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.FieldDefinition
import graphql.language.Node
import graphql.language.ObjectTypeDefinition
import graphql.parser.exceptions.ParseCancelledException
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import spock.lang.Specification

class WhitespaceTokenSkippingTest extends Specification {

    static String largeSchema = WhitespaceTokenSkippingTest.class.getResourceAsStream("/large-schema-3.graphqls").getText("UTF-8")

    static ParserOptions sdlOptions = ParserOptions.getDefaultSdlParserOptions()
    static ParserOptions captureIgnoredChars = sdlOptions.transform { it.captureIgnoredChars(true) }

    private static Document parse(Parser parser, String document, ParserOptions parserOptions) {
        def environment = ParserEnvironment.newParserEnvironment().document(document).parserOptions(parserOptions).build()
        parser.parseDocument(environment)
    }

    private static List<String> describeNodes(Node<?> node) {
        def comments = node.getComments().collect { [it.getContent(), it.getSourceLocation()] }
        def ignoredChars = [node.getIgnoredChars().getLeft(), node.getIgnoredChars().getRight()]
        def description = [node.getClass().getSimpleName(), node.getSourceLocation(), comments, ignoredChars].toString()
        [description] + node.getChildren().collectMany { describeNodes(it) }
    }

    private static List<Token> whitespaceTokens(CommonTokenStream tokens) {
        tokens.getTokens().findAll { it.getChannel() == Parser.CHANNEL_WHITESPACE }
    }

    def "whitespace tokens only reach the token stream when ignored chars are captured"() {
        given:
        def parser = new TokenRecordingParser()

        when:
        parse(parser, "type Query {\n  f: String, g: Int\n}", sdlOptions)

        then:
        whitespaceTokens(parser.tokens).isEmpty()

        when:
        parse(parser, "type Query {\n  f: String, g: Int\n}", captureIgnoredChars)

        then:
        whitespaceTokens(parser.tokens).collect { it.getText() } == [" ", " ", "\n", " ", " ", " ", ",", " ", " ", "\n"]
    }

    def "a parser subclass can ask for the whitespace tokens"() {
        given:
        def parser = new TokenRecordingParser(keepWhitespaceTokens: true)

        when:
        parse(parser, "type Query {\n  f: String, g: Int\n}", sdlOptions)

        then:
        whitespaceTokens(parser.tokens).size() == 10
    }

    def "parsing a large schema gives the same AST whether or not whitespace tokens are kept"() {
        given:
        def keepingParser = new TokenRecordingParser(keepWhitespaceTokens: true)

        when:
        def skipped = parse(new Parser(), largeSchema, sdlOptions)
        def kept = parse(keepingParser, largeSchema, sdlOptions)

        then:
        !whitespaceTokens(keepingParser.tokens).isEmpty()
        skipped.isEqualTo(kept)
        AstPrinter.printAst(skipped) == AstPrinter.printAst(kept)
        describeNodes(skipped) == describeNodes(kept)
    }

    def "comments are still attached when whitespace tokens are skipped"() {
        given:
        def sdl = '''
            # leading comment
            type Query { # not attached to a field
                # field comment 1
                # field comment 2
                f: String, g: Int
            }
        '''

        when:
        def document = parse(new Parser(), sdl, sdlOptions)
        def type = document.getDefinitionsOfType(ObjectTypeDefinition)[0]
        def fields = type.getFieldDefinitions()

        then:
        type.getComments().collect { it.getContent() } == [" leading comment"]
        fields[0].getComments().collect { it.getContent() } == [" not attached to a field", " field comment 1", " field comment 2"]
        fields[1].getComments().isEmpty()
        describeNodes(document) == describeNodes(parse(new TokenRecordingParser(keepWhitespaceTokens: true), sdl, sdlOptions))
    }

    def "the whitespace limit is enforced the same way whether or not whitespace tokens are kept"() {
        given:
        def document = "query {\n  f,\tg\n}"
        def options = ParserOptions.newParserOptions()
                .captureIgnoredChars(captureIgnoredCharsOn)
                .maxWhitespaceTokens(5)
                .build()

        when:
        parse(new Parser(), document, options)

        then:
        def e = thrown(ParseCancelledException)
        e.getMessage().startsWith("More than 5 'whitespace' tokens have been presented.")
        e.getOffendingToken() == "\t"
        e.getLocation().getLine() == 2
        e.getLocation().getColumn() == 5

        where:
        captureIgnoredCharsOn << [false, true]
    }

    def "whitespace up to the limit is allowed whether or not whitespace tokens are kept"() {
        given:
        def options = ParserOptions.newParserOptions()
                .captureIgnoredChars(captureIgnoredCharsOn)
                .maxWhitespaceTokens(7)
                .build()

        when:
        def document = parse(new Parser(), "query {\n  f,\tg\n}", options)

        then:
        document != null

        where:
        captureIgnoredCharsOn << [false, true]
    }

    def "the default operation whitespace limit applies whether or not whitespace tokens are kept"() {
        given:
        def text = "query { f ${" " * 200_000} }"
        def options = ParserOptions.getDefaultOperationParserOptions().transform { it.captureIgnoredChars(captureIgnoredCharsOn) }

        when:
        parse(new Parser(), text, options)

        then:
        def e = thrown(ParseCancelledException)
        e.getMessage().contains("'whitespace' tokens have been presented")
        e.getOffendingToken() == " "
        e.getLocation().getColumn() == 200_008

        where:
        captureIgnoredCharsOn << [false, true]
    }

    def "extra tokens after the document are still reported"() {
        given:
        def options = ParserOptions.newParserOptions().captureIgnoredChars(captureIgnoredCharsOn).build()

        when:
        parse(new Parser(), "{profile(id:117) {computer, says, no}} \n }", options)

        then:
        InvalidSyntaxException e = thrown(InvalidSyntaxException)
        e.message == "Invalid syntax encountered. There are extra tokens in the text that have not been consumed. Offending token '}' at line 2 column 2"

        where:
        captureIgnoredCharsOn << [false, true]
    }

    def "trailing whitespace and comments after the document are allowed"() {
        when:
        def document = Parser.parse("{ f } , \n # trailing comment \n\t")

        then:
        document.getDefinitions().size() == 1
    }

    def "extra tokens after a value are still reported"() {
        when:
        Parser.parseValue("1 2")

        then:
        thrown(InvalidSyntaxException)

        when:
        def value = Parser.parseValue(" 1 , ")

        then:
        value != null
    }

    def "field definitions parse the same when whitespace tokens are skipped"() {
        when:
        FieldDefinition fieldDefinition = Parser.parseFieldDefinition("f(a: Int,\n b: String): String")

        then:
        fieldDefinition.getInputValueDefinitions().collect { it.getName() } == ["a", "b"]
        fieldDefinition.getInputValueDefinitions()[1].getSourceLocation().getLine() == 2
        fieldDefinition.getInputValueDefinitions()[1].getSourceLocation().getColumn() == 2
    }
}

class TokenRecordingParser extends Parser {
    boolean keepWhitespaceTokens
    CommonTokenStream tokens

    @Override
    protected boolean isWhitespaceTokensRequired(ParserOptions parserOptions) {
        return keepWhitespaceTokens || super.isWhitespaceTokensRequired(parserOptions)
    }

    @Override
    protected GraphqlAntlrToLanguage getAntlrToLanguage(CommonTokenStream tokens, MultiSourceReader multiSourceReader, ParserEnvironment environment) {
        this.tokens = tokens
        return super.getAntlrToLanguage(tokens, multiSourceReader, environment)
    }
}
