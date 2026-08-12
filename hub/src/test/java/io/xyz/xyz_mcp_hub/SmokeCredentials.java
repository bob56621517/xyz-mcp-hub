package io.xyz.xyz_mcp_hub;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * 冒烟凭据读取器：统一各 main 冒烟从何处取 API key / token。
 *
 * <p>语义与 Spring 运行时一致（ADR-0005）：**环境变量优先**，application-local.yml 兜底——对应
 * {@code application.yaml} 占位符 {@code ${KEY:}} 的解析链（OS 环境变量 &gt; application-{@code profile}.yml）。
 * 因此冒烟看到的凭据与 Hub 应用启动时一致：配 env 或本地 yaml 均可，env 优先。</p>
 *
 * <p>application-local.yml 为 gitignored 本地敏感配置；不存在（CI/其他机器）时自然回退环境变量。
 * 定位候选：仓库根 {@code hub/src/main/resources/}、模块 basedir {@code src/main/resources/}，
 * 最后测试 classpath 根（resources 已复制进 target/classes，可能滞后于源文件，故排最后）。</p>
 */
final class SmokeCredentials {

	private static volatile Map<String, Object> yaml;

	private SmokeCredentials() {
	}

	/** 取凭据：环境变量优先；未设/空白则读 application-local.yml。 */
	static String get(String key) {
		String fromEnv = System.getenv(key);
		if (fromEnv != null && !fromEnv.isBlank()) {
			return fromEnv;
		}
		Object value = localYaml().get(key);
		return value == null ? null : String.valueOf(value);
	}

	private static Map<String, Object> localYaml() {
		Map<String, Object> loaded = yaml;
		if (loaded == null) {
			synchronized (SmokeCredentials.class) {
				loaded = yaml;
				if (loaded == null) {
					loaded = loadYaml();
					yaml = loaded;
				}
			}
		}
		return loaded;
	}

	private static Map<String, Object> loadYaml() {
		Yaml parser = new Yaml();
		// 1) 文件系统候选（真实来源，优先）：仓库根 / 模块 basedir
		for (Path candidate : yamlCandidates()) {
			if (Files.exists(candidate)) {
				try (InputStream in = Files.newInputStream(candidate)) {
					return toMap(parser.load(in));
				}
				catch (IOException ignored) {
					// 读取失败则尝试下一个候选
				}
			}
		}
		// 2) classpath 兜底（target/classes 复制件，可能滞后）
		try (InputStream in = SmokeCredentials.class.getResourceAsStream("/application-local.yml")) {
			if (in != null) {
				return toMap(parser.load(in));
			}
		}
		catch (IOException ignored) {
			// 无 classpath 拷贝则回退空映射
		}
		return Collections.emptyMap();
	}

	private static List<Path> yamlCandidates() {
		return List.of(
				Path.of("hub/src/main/resources/application-local.yml"),
				Path.of("src/main/resources/application-local.yml"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> toMap(Object root) {
		if (root instanceof Map<?, ?> map) {
			return Collections.unmodifiableMap((Map<String, Object>) map);
		}
		return Collections.emptyMap();
	}
}
