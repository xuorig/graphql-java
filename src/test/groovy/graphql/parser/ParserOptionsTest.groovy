package graphql.parser

import graphql.AssertException
import spock.lang.Specification

class ParserOptionsTest extends Specification {
    static defaultOptions = ParserOptions.getDefaultParserOptions()
    static defaultOperationOptions = ParserOptions.getDefaultOperationParserOptions()
    static defaultSdlOptions = ParserOptions.getDefaultSdlParserOptions()

    static final int ONE_MB = 1024 * 1024

    void setup() {
        ParserOptions.setDefaultParserOptions(defaultOptions)
        ParserOptions.setDefaultOperationParserOptions(defaultOperationOptions)
        ParserOptions.setDefaultSdlParserOptions(defaultSdlOptions)
    }

    void cleanup() {
        ParserOptions.setDefaultParserOptions(defaultOptions)
        ParserOptions.setDefaultOperationParserOptions(defaultOperationOptions)
        ParserOptions.setDefaultSdlParserOptions(defaultSdlOptions)
    }

    def "lock in default settings"() {
        expect:
        defaultOptions.getMaxCharacters() == ONE_MB
        defaultOptions.getMaxTokens() == 15_000
        defaultOptions.getMaxWhitespaceTokens() == 200_000
        defaultOptions.getMaxNumericLiteralCharacters() == 100
        defaultOptions.isCaptureSourceLocation()
        defaultOptions.isCaptureLineComments()
        !defaultOptions.isCaptureIgnoredChars()
        defaultOptions.isReaderTrackData()
        defaultOptions.getReaderBufferSize() == 8192
        !defaultOptions.isRedactTokenParserErrorMessages()

        defaultOperationOptions.getMaxTokens() == 15_000
        defaultOperationOptions.getMaxWhitespaceTokens() == 200_000
        defaultOperationOptions.getMaxNumericLiteralCharacters() == 100
        defaultOperationOptions.isCaptureSourceLocation()
        !defaultOperationOptions.isCaptureLineComments()
        !defaultOperationOptions.isCaptureIgnoredChars()
        defaultOperationOptions.isReaderTrackData()
        defaultOperationOptions.getReaderBufferSize() == 8192
        !defaultOperationOptions.isRedactTokenParserErrorMessages()

        defaultSdlOptions.getMaxCharacters() == Integer.MAX_VALUE
        defaultSdlOptions.getMaxTokens() == Integer.MAX_VALUE
        defaultSdlOptions.getMaxWhitespaceTokens() == Integer.MAX_VALUE
        defaultSdlOptions.getMaxNumericLiteralCharacters() == 100
        defaultSdlOptions.isCaptureSourceLocation()
        defaultSdlOptions.isCaptureLineComments()
        !defaultSdlOptions.isCaptureIgnoredChars()
        defaultSdlOptions.isReaderTrackData()
        defaultSdlOptions.getReaderBufferSize() == 8192
        !defaultSdlOptions.isRedactTokenParserErrorMessages()
    }

    def "can set in new option JVM wide"() {
        def newDefaultOptions = defaultOptions.transform({
            it.captureIgnoredChars(true)
                    .readerTrackData(false)
                    .redactTokenParserErrorMessages(true)
        })
        def newDefaultOperationOptions = defaultOperationOptions.transform(
                {
                    it.captureIgnoredChars(true)
                            .captureLineComments(true)
                            .maxCharacters(1_000_000)
                            .maxNumericLiteralCharacters(200)
                            .maxWhitespaceTokens(300_000)
                })
        def newDefaultSDlOptions = defaultSdlOptions.transform(
                {
                    it.captureIgnoredChars(true)
                            .captureLineComments(true)
                            .maxWhitespaceTokens(300_000)
                })

        when:
        ParserOptions.setDefaultParserOptions(newDefaultOptions)
        ParserOptions.setDefaultOperationParserOptions(newDefaultOperationOptions)
        ParserOptions.setDefaultSdlParserOptions(newDefaultSDlOptions)

        def currentDefaultOptions = ParserOptions.getDefaultParserOptions()
        def currentDefaultOperationOptions = ParserOptions.getDefaultOperationParserOptions()
        def currentDefaultSdlOptions = ParserOptions.getDefaultSdlParserOptions()

        then:

        currentDefaultOptions.getMaxCharacters() == ONE_MB
        currentDefaultOptions.getMaxTokens() == 15_000
        currentDefaultOptions.getMaxWhitespaceTokens() == 200_000
        currentDefaultOptions.getMaxNumericLiteralCharacters() == 100
        currentDefaultOptions.isCaptureSourceLocation()
        currentDefaultOptions.isCaptureLineComments()
        currentDefaultOptions.isCaptureIgnoredChars()
        !currentDefaultOptions.isReaderTrackData()
        currentDefaultOptions.isRedactTokenParserErrorMessages()

        currentDefaultOperationOptions.getMaxCharacters() == 1_000_000
        currentDefaultOperationOptions.getMaxTokens() == 15_000
        currentDefaultOperationOptions.getMaxWhitespaceTokens() == 300_000
        currentDefaultOperationOptions.getMaxNumericLiteralCharacters() == 200
        currentDefaultOperationOptions.isCaptureSourceLocation()
        currentDefaultOperationOptions.isCaptureLineComments()
        currentDefaultOperationOptions.isCaptureIgnoredChars()
        currentDefaultOperationOptions.isReaderTrackData()
        !currentDefaultOperationOptions.isRedactTokenParserErrorMessages()

        currentDefaultSdlOptions.getMaxCharacters() == Integer.MAX_VALUE
        currentDefaultSdlOptions.getMaxTokens() == Integer.MAX_VALUE
        currentDefaultSdlOptions.getMaxWhitespaceTokens() == 300_000
        currentDefaultSdlOptions.getMaxNumericLiteralCharacters() == 100
        currentDefaultSdlOptions.isCaptureSourceLocation()
        currentDefaultSdlOptions.isCaptureLineComments()
        currentDefaultSdlOptions.isCaptureIgnoredChars()
        currentDefaultSdlOptions.isReaderTrackData()
        !currentDefaultSdlOptions.isRedactTokenParserErrorMessages()
    }

    def "transform() round trips readerTrackData and readerBufferSize without them being re-set"() {
        given:
        def options = ParserOptions.newParserOptions()
                .readerTrackData(false)
                .readerBufferSize(1024)
                .build()

        when:
        // transform() only touches captureIgnoredChars - readerTrackData and readerBufferSize
        // must survive via the Builder(ParserOptions) copy constructor, not because we set them again here
        def transformed = options.transform({ it.captureIgnoredChars(true) })

        then:
        !transformed.isReaderTrackData()
        transformed.getReaderBufferSize() == 1024
    }

    def "readerBufferSize rejects sizes below 2"() {
        when:
        ParserOptions.newParserOptions().readerBufferSize(size)

        then:
        thrown(AssertException)

        where:
        size << [0, 1, -1, Integer.MIN_VALUE]
    }
}
