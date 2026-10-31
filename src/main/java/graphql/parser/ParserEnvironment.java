package graphql.parser;

import graphql.ExperimentalApi;
import graphql.PublicApi;
import graphql.i18n.I18n;
import org.jspecify.annotations.Nullable;

import java.io.Reader;
import java.io.StringReader;
import java.util.Locale;

import static graphql.Assert.assertNotNull;

/**
 * This is the arguments that can be passed to a {@link Parser}
 */
@PublicApi
public interface ParserEnvironment {

    /**
     * @return the document to be parsed
     */
    Reader getDocument();

    /**
     * @return the parsing options
     */
    ParserOptions getParserOptions();

    /**
     * @return the locale to produce parsing error messages in
     */
    Locale getLocale();

    /**
     * @return the {@link I18n} to produce parsing error messages with
     */
    I18n getI18N();

    /**
     * @return the buffer to read the document into, or null to use a new one for this parse
     */
    @ExperimentalApi
    default @Nullable ParserBuffer getParserBuffer() {
        return null;
    }

    /**
     * @return a builder of new parsing options
     */
    static Builder newParserEnvironment() {
        return new Builder();
    }

    class Builder {
        Reader reader;
        ParserOptions parserOptions = ParserOptions.getDefaultParserOptions();
        Locale locale = Locale.getDefault();
        ParserBuffer parserBuffer;

        public Builder() {
        }

        public Builder document(Reader documentText) {
            this.reader = assertNotNull(documentText);
            return this;
        }

        public Builder document(String documentText) {
            return document(new StringReader(documentText));
        }

        public Builder parserOptions(ParserOptions parserOptions) {
            this.parserOptions = parserOptions;
            return this;
        }

        public Builder locale(Locale locale) {
            this.locale = assertNotNull(locale);
            return this;
        }

        /**
         * Sets a buffer that is reused across parses to read documents into, see {@link ParserBuffer}
         *
         * @param parserBuffer the buffer to read the document into
         *
         * @return this builder
         */
        @ExperimentalApi
        public Builder parserBuffer(ParserBuffer parserBuffer) {
            this.parserBuffer = assertNotNull(parserBuffer);
            return this;
        }

        public ParserEnvironment build() {
            I18n i18n = I18n.i18n(I18n.BundleType.Parsing, locale);
            return new ParserEnvironment() {
                @Override
                public Reader getDocument() {
                    return reader;
                }

                @Override
                public ParserOptions getParserOptions() {
                    return parserOptions;
                }

                @Override
                public Locale getLocale() {
                    return locale;
                }

                @Override
                public I18n getI18N() {
                    return i18n;
                }

                @Override
                public @Nullable ParserBuffer getParserBuffer() {
                    return parserBuffer;
                }
            };
        }
    }
}
