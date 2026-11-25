package com.example.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Second pass of the two-pass table of contents build.
 *
 * <p>Flying Saucer's {@code target-counter()} crashes on generated inline
 * content in 9.1.22, so page numbers are resolved after the fact: this tool
 * extracts the text of every page, finds the last occurrence of each heading
 * and prints {@code anchor<TAB>page} lines.</p>
 *
 * <p>Usage: {@code PdfPageIndex <file.pdf> <mapping.tsv> <out.tsv>}</p>
 */
public final class PdfPageIndex {

    private PdfPageIndex() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: PdfPageIndex <file.pdf> <mapping.tsv> <out.tsv>");
            System.exit(2);
        }
        Path pdf = Path.of(args[0]);
        Path mapping = Path.of(args[1]);
        Path out = Path.of(args[2]);

        List<String[]> entries = new ArrayList<>();
        for (String line : Files.readAllLines(mapping, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", 2);
            entries.add(new String[]{parts[0], normalize(parts[1])});
        }

        StringBuilder result = new StringBuilder();
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            int pages = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            List<String> pageText = new ArrayList<>(pages + 1);
            pageText.add("");
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pageText.add(normalize(stripper.getText(document)));
            }

            for (String[] entry : entries) {
                int found = 0;
                for (int page = pages; page >= 1; page--) {
                    if (pageText.get(page).contains(entry[1])) {
                        found = page;
                        break;
                    }
                }
                result.append(entry[0]).append('\t').append(found).append('\n');
                System.out.printf("[index] %s -> page %d%n", entry[1], found);
            }
        }
        Files.writeString(out, result.toString(), StandardCharsets.UTF_8);
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", "");
    }
}
