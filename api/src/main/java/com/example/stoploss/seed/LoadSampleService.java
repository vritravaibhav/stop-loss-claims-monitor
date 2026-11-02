package com.example.stoploss.seed;

import com.example.stoploss.upload.IngestService;
import com.example.stoploss.upload.UploadResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/** Load every claim file in a directory, in file-name order, through the same ingest code as the HTTP upload. */
@Service
public class LoadSampleService {
  private final IngestService ingest;

  public LoadSampleService(IngestService ingest) {
    this.ingest = ingest;
  }

  /**
   * File-name order = plain code-unit order ({@link String#compareTo}). A locale-aware collation would put
   * {@code GA_2026-04_resend.csv} <em>before</em> {@code GA_2026-04.csv} because it ignores punctuation at the first
   * level.
   */
  public static List<String> listClaimFiles(Path dir) throws IOException {
    try (Stream<Path> entries = Files.list(dir)) {
      return entries
          .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) // like Dirent.isFile()
          .map(p -> p.getFileName().toString())
          .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".csv"))
          .sorted()
          .toList();
    }
  }

  public List<UploadResult> loadSampleClaims(Path dir, String uploadedBy, Consumer<UploadResult> onFile)
      throws IOException {
    List<UploadResult> out = new ArrayList<>();
    for (String name : listClaimFiles(dir)) {
      UploadResult r = ingest.ingest(name, Files.readAllBytes(dir.resolve(name)), uploadedBy);
      onFile.accept(r);
      out.add(r);
    }
    return out;
  }
}
