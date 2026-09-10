package smoke;

import io.github.thirtyeighttwentysix.volan.codegen.VolanGenerator;
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader;
import io.github.thirtyeighttwentysix.volan.schema.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;

public class GenerateClient {
    public static void main(String[] args) throws Exception {
        var source = new SourceFile("schema.volan", Files.readString(Path.of(args[0])));
        VolanGenerator.writeTo(SchemaLoader.loadOrThrow(source), Path.of(args[1]));
    }
}
