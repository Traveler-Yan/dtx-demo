package com.example.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders each PDF page to a PNG so the result can be inspected visually.
 *
 * <p>Usage: {@code PdfPreview <input.pdf> <outputDir> [dpi] [maxPages]}</p>
 */
public final class PdfPreview {

    private PdfPreview() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PdfPreview <input.pdf> <outputDir> [dpi] [maxPages]");
            System.exit(2);
        }
        Path pdf = Path.of(args[0]).toAbsolutePath();
        Path outDir = Path.of(args[1]).toAbsolutePath();
        float dpi = args.length > 2 ? Float.parseFloat(args[2]) : 110f;
        int maxPages = args.length > 3 ? Integer.parseInt(args[3]) : Integer.MAX_VALUE;

        Files.createDirectories(outDir);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            System.out.println("[preview] pages = " + doc.getNumberOfPages());
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                var box = doc.getPage(i).getMediaBox();
                System.out.printf("[preview] page %d box = %.1f x %.1f pt%n", i + 1, box.getWidth(), box.getHeight());
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            int limit = Math.min(doc.getNumberOfPages(), maxPages);
            for (int i = 0; i < limit; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi);
                File out = outDir.resolve(String.format("page-%02d.png", i + 1)).toFile();
                ImageIO.write(image, "png", out);
                System.out.println("[preview] " + out.getName() + " " + image.getWidth() + "x" + image.getHeight());
            }
        }
    }
}
