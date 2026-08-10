package io.xyz.xyz_mcp_hub.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ContainerSpecReader} 单测：manifest（manifests/mcp-images.yaml 形态）解析为 ContainerSpec、
 * schema 校验（protocol 枚举、必填字段、端口越界）、缺失文件降级为空。纯 JVM、无 Spring、无 docker。
 */
class ContainerSpecReaderTest {

	@TempDir
	Path tmp;

	private static final String TWO_IMAGES = """
		images:
		  markitdown:
		    image: xyz-mcp-hub/markitdown:latest
		    protocol: mcp
		    port: 3001
		    hostPort: 13001
		  jina:
		    image: ghcr.io/jina-ai/reader:latest
		    protocol: rest
		    port: 8081
		    hostPort: 18081
		""";

	@Test
	void parsesManifestIntoSpecs() throws IOException {
		ContainerSpecReader reader = new ContainerSpecReader(manifest(TWO_IMAGES));

		Map<String, ContainerSpec> specs = reader.readAll();
		assertThat(specs).hasSize(2);

		ContainerSpec markitdown = specs.get("markitdown");
		assertThat(markitdown.image()).isEqualTo("xyz-mcp-hub/markitdown:latest");
		assertThat(markitdown.protocol()).isEqualTo(Protocol.MCP);
		assertThat(markitdown.port()).isEqualTo(3001);
		assertThat(markitdown.hostPort()).isEqualTo(13001);
		assertThat(markitdown.containerName()).isEqualTo("xyz-hub-markitdown");

		ContainerSpec jina = specs.get("jina");
		assertThat(jina.protocol()).isEqualTo(Protocol.REST);
		assertThat(jina.hostPort()).isEqualTo(18081);
	}

	@Test
	void byNameReturnsSpecOrEmpty() throws IOException {
		ContainerSpecReader reader = new ContainerSpecReader(manifest(TWO_IMAGES));
		assertThat(reader.byName("jina"))
			.get()
			.extracting(ContainerSpec::image)
			.isEqualTo("ghcr.io/jina-ai/reader:latest");
		assertThat(reader.byName("nonexistent")).isEmpty();
	}

	@Test
	void missingManifestReturnsEmptyMap() {
		ContainerSpecReader reader = new ContainerSpecReader(tmp.resolve("no-such-manifest.yaml"));
		assertThat(reader.readAll()).isEmpty();
	}

	@Test
	void protocolIsCaseInsensitive() throws IOException {
		ContainerSpecReader reader = new ContainerSpecReader(manifest("""
			images:
			  m:
			    image: x
			    protocol: REST
			    port: 1
			    hostPort: 10001
			"""));
		assertThat(reader.readAll().get("m").protocol()).isEqualTo(Protocol.REST);
	}

	@Test
	void quotedPortNumbersAreCoerced() throws IOException {
		ContainerSpecReader reader = new ContainerSpecReader(manifest("""
			images:
			  m:
			    image: x
			    protocol: mcp
			    port: "3001"
			    hostPort: "13001"
			"""));
		ContainerSpec spec = reader.readAll().get("m");
		assertThat(spec.port()).isEqualTo(3001);
		assertThat(spec.hostPort()).isEqualTo(13001);
	}

	@Test
	void unknownProtocolRejected() throws IOException {
		ContainerSpecReader reader = new ContainerSpecReader(manifest("""
			images:
			  bad:
			    image: x
			    protocol: ftp
			    port: 1
			    hostPort: 10001
			"""));
		assertThatThrownBy(reader::readAll)
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("protocol");
	}

	@Test
	void missingImageRejected() throws IOException {
		ContainerSpecReader reader = new ContainerSpecReader(manifest("""
			images:
			  bad:
			    protocol: mcp
			    port: 1
			    hostPort: 10001
			"""));
		assertThatThrownBy(reader::readAll)
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("image");
	}

	@Test
	void missingImagesNodeRejected() throws IOException {
		ContainerSpecReader reader = new ContainerSpecReader(manifest("other: value\n"));
		assertThatThrownBy(reader::readAll)
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("images");
	}

	@Test
	void portOutOfRangeRejected() throws IOException {
		ContainerSpecReader reader = new ContainerSpecReader(manifest("""
			images:
			  bad:
			    image: x
			    protocol: mcp
			    port: 0
			    hostPort: 10001
			"""));
		assertThatThrownBy(reader::readAll).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void hostPortMustBeFiveDigits() throws IOException {
		ContainerSpecReader reader = new ContainerSpecReader(manifest("""
			images:
			  bad:
			    image: x
			    protocol: mcp
			    port: 3001
			    hostPort: 9999
			"""));
		assertThatThrownBy(reader::readAll)
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("5 位数");
	}

	private Path manifest(String yaml) throws IOException {
		Path path = tmp.resolve("mcp-images.yaml");
		Files.writeString(path, yaml);
		return path;
	}
}
