package benchmark;

import graphql.language.Document;
import graphql.parser.Parser;
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

@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 3)
@Fork(2)
@State(Scope.Benchmark)
public class ParserBenchmark {

    @Param({"large-schema-2.graphqls", "large-schema-3.graphqls"})
    public String schemaFile;

    private String sdl;

    @Setup
    public void setup() {
        sdl = BenchmarkUtils.loadResource(schemaFile);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Document parseSdl() {
        return parse(ParserOptions.getDefaultSdlParserOptions());
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Document parseSdlCapturingIgnoredChars() {
        return parse(ParserOptions.getDefaultSdlParserOptions().transform(builder -> builder.captureIgnoredChars(true)));
    }

    private Document parse(ParserOptions parserOptions) {
        ParserEnvironment parserEnvironment = ParserEnvironment.newParserEnvironment()
                .document(sdl)
                .parserOptions(parserOptions)
                .build();
        return new Parser().parseDocument(parserEnvironment);
    }
}
