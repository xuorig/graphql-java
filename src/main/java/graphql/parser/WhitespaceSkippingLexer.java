package graphql.parser;

import graphql.Internal;
import graphql.parser.antlr.GraphqlLexer;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Token;

import java.util.function.BiConsumer;

/**
 * A lexer that drops whitespace tokens instead of emitting them on {@link Parser#CHANNEL_WHITESPACE}.
 * <p>
 * Each space, tab, comma and line terminator is its own token, so on typical documents most of the lexer output
 * is whitespace. When ignored characters are not being captured nothing reads those tokens, and skipping them
 * saves creating them, passing them through {@link SafeTokenSource} and buffering them in the token stream.
 * <p>
 * Skipped whitespace still counts towards the maximum number of whitespace tokens. The first whitespace token
 * over that limit is emitted as usual and handed to the callback, as {@link SafeTokenSource} would have done.
 */
@Internal
public class WhitespaceSkippingLexer extends GraphqlLexer {

    private final int maxWhitespaceTokens;
    private final BiConsumer<Integer, Token> whenMaxWhitespaceTokensExceeded;
    private int whitespaceTokenCount;

    public WhitespaceSkippingLexer(CharStream input, int maxWhitespaceTokens, BiConsumer<Integer, Token> whenMaxWhitespaceTokensExceeded) {
        super(input);
        this.maxWhitespaceTokens = maxWhitespaceTokens;
        this.whenMaxWhitespaceTokensExceeded = whenMaxWhitespaceTokensExceeded;
    }

    // the grammar's channel(3) lexer command reaches the lexer through this method
    @Override
    public void setChannel(int channel) {
        super.setChannel(channel);
        if (channel != Parser.CHANNEL_WHITESPACE) {
            return;
        }
        whitespaceTokenCount++;
        if (whitespaceTokenCount <= maxWhitespaceTokens) {
            skip();
        }
    }

    @Override
    public Token nextToken() {
        Token token = super.nextToken();
        if (token.getChannel() == Parser.CHANNEL_WHITESPACE) {
            whenMaxWhitespaceTokensExceeded.accept(maxWhitespaceTokens, token);
        }
        return token;
    }
}
