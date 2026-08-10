package io.xyz.xyz_mcp_hub.docker;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * 容器运行规范清单读取器：解析 mvn 生成的 {@code manifests/mcp-images.yaml}（ADR-0012 / #31）为
 * {@code name → ContainerSpec} 映射。清单由模板 {@code @token@} 替换生成、schema 固定；缺失文件视为
 * 「无容器规格」返回空映射（开发环境可能未跑 verify），文件存在但 schema 损坏则 fail-fast 抛异常。
 */
@Component
public class ContainerSpecReader {

	private static final Logger log = LoggerFactory.getLogger(ContainerSpecReader.class);

	private final Path manifestPath;

	@Autowired
	public ContainerSpecReader(DockerProperties properties) {
		this(Path.of(properties.getManifestPath()));
	}

	/** 测试可注入任意路径。 */
	public ContainerSpecReader(Path manifestPath) {
		this.manifestPath = manifestPath;
	}

	public Path manifestPath() {
		return manifestPath;
	}

	/** 读取全部容器规格；清单文件缺失时返回空映射（warn 日志，不抛）。 */
	public Map<String, ContainerSpec> readAll() {
		if (!Files.exists(manifestPath)) {
			log.warn("docker 镜像清单不存在：{}（由根聚合 verify 生成，见 ADR-0012），无容器规格", manifestPath);
			return Map.of();
		}
		try (Reader reader = Files.newBufferedReader(manifestPath)) {
			Object loaded = new Yaml().load(reader);
			if (!(loaded instanceof Map<?, ?> root)) {
				throw new IllegalArgumentException("镜像清单顶层不是映射：" + manifestPath);
			}
			Object imagesNode = root.get("images");
			if (!(imagesNode instanceof Map<?, ?> images)) {
				throw new IllegalArgumentException("镜像清单缺少 images: 节点：" + manifestPath);
			}
			Map<String, ContainerSpec> specs = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : images.entrySet()) {
				String name = String.valueOf(entry.getKey());
				if (!(entry.getValue() instanceof Map<?, ?> fields)) {
					throw new IllegalArgumentException("镜像清单节点 " + name + " 不是映射");
				}
				specs.put(name, parse(name, fields));
			}
			return Map.copyOf(specs);
		}
		catch (IOException e) {
			throw new IllegalStateException("读取镜像清单失败：" + manifestPath, e);
		}
	}

	/** 按名取单个容器规格。 */
	public Optional<ContainerSpec> byName(String name) {
		return Optional.ofNullable(readAll().get(name));
	}

	private static ContainerSpec parse(String name, Map<?, ?> fields) {
		String image = requireString(fields, "image");
		String protocol = requireString(fields, "protocol");
		int port = requireInt(fields, "port");
		int hostPort = requireInt(fields, "hostPort");
		return new ContainerSpec(name, image, Protocol.parse(protocol), port, hostPort);
	}

	private static String requireString(Map<?, ?> fields, String key) {
		Object value = fields.get(key);
		if (value == null || String.valueOf(value).isBlank()) {
			throw new IllegalArgumentException("镜像清单节点缺少 " + key + " 字段");
		}
		return String.valueOf(value).trim();
	}

	private static int requireInt(Map<?, ?> fields, String key) {
		Object value = fields.get(key);
		if (value == null) {
			throw new IllegalArgumentException("镜像清单节点缺少 " + key + " 字段");
		}
		if (value instanceof Number number) {
			return number.intValue();
		}
		try {
			return Integer.parseInt(String.valueOf(value).trim());
		}
		catch (NumberFormatException e) {
			throw new IllegalArgumentException("镜像清单 " + key + " 不是整数：" + value);
		}
	}
}
