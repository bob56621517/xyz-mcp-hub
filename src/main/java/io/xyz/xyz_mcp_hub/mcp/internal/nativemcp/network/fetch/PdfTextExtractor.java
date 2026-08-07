package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.fetch;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * PDF 文本提取（Apache PDFBox，ADR-0010 决策「PDF→PDFBox」）。
 * 从 PDF 字节流提取纯文本，供 fetch 端点的 PDF 文档类型路由使用。
 */
@Component
public class PdfTextExtractor {

	/** 提取失败（损坏/加密/非 PDF）时抛 {@link FetchException}。 */
	public String extract(byte[] pdfBytes) {
		try (PDDocument document = PDDocument.load(pdfBytes)) {
			if (document.isEncrypted()) {
				throw new FetchException("PDF 已加密，暂不支持解析。");
			}
			return new PDFTextStripper().getText(document);
		}
		catch (IOException e) {
			throw new FetchException("PDF 解析失败：" + e.getMessage(), e);
		}
	}
}
