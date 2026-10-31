package benchmark;

import graphql.language.Document;
import graphql.parser.Parser;
import graphql.parser.ParserBuffer;
import graphql.parser.ParserEnvironment;
import graphql.parser.ParserOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 4, time = 2)
@Fork(2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ParserBufferBenchmark {

    @Param({"small-query", "large-schema-2", "large-schema-3", "large-schema-3-emoji", "large-schema-4"})
    public String document;

    private String documentText;
    private ParserOptions parserOptions;
    private final ParserBuffer parserBuffer = ParserBuffer.newParserBuffer();

    @Setup
    public void setup() {
        if (document.equals("small-query")) {
            documentText = BenchmarkUtils.loadResource("large-schema-1-query.graphql");
            parserOptions = ParserOptions.getDefaultOperationParserOptions();
            return;
        }
        parserOptions = ParserOptions.getDefaultSdlParserOptions();
        if (document.equals("large-schema-3-emoji")) {
            // a character outside the Basic Multilingual Plane makes the whole document use int storage
            documentText = "# 😀\n" + BenchmarkUtils.loadResource("large-schema-3.graphqls");
            return;
        }
        documentText = BenchmarkUtils.loadResource(document + ".graphqls");
    }

    @Benchmark
    public Document parseWithoutBuffer() {
        return Parser.parse(newParserEnvironment().build());
    }

    @Benchmark
    public Document parseWithReusedBuffer() {
        return Parser.parse(newParserEnvironment().parserBuffer(parserBuffer).build());
    }

    private ParserEnvironment.Builder newParserEnvironment() {
        return ParserEnvironment.newParserEnvironment()
                .document(documentText)
                .parserOptions(parserOptions);
    }
}
