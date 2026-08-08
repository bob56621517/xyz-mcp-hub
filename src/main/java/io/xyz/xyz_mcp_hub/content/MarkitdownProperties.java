package io.xyz.xyz_mcp_hub.content;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * markitdown 转换服务配置（前缀 {@code content.markitdown}）。markitdown 以本地子进程 HTTP
 * 服务形式运行（Streamable HTTP，见 issue #25），JVM 侧经 spring-ai MCP client 转发调用。
 *
 * <p>{@code host} 固定 {@code localhost}，属安全硬约束不可配：markitdown 只允许访问本机，
 * 避免暴露为可被外部访问的转换服务。自定义 {@code command} 时须保证其监听端口与
 * {@code port} 一致，否则健康检查与转发连接会失败。</p>
 */
@ConfigurationProperties(prefix = "content.markitdown")
public class MarkitdownProperties {

	/** markitdown 服务默认端口。 */
	public static final int DEFAULT_PORT = 3001;

	/** 服务监听地址，安全硬约束：仅本机，不可配置。 */
	public static final String HOST = "localhost";

	/** 默认启动命令：uvx 按需解析依赖（含 mcp converter），--http 起 Streamable HTTP。 */
	public static final String DEFAULT_COMMAND =
			"uvx --with mcp<2.0.0 markitdown-mcp --http --port 3001 --host localhost";

	/** 服务监听端口（默认 3001）。 */
	private int port = DEFAULT_PORT;

	/** 启动子进程的完整命令行（默认 {@link #DEFAULT_COMMAND}），按空白分词、不经 shell。 */
	private String command = DEFAULT_COMMAND;

	/** 是否启用 markitdown 转换能力；false 时不拉起子进程、不注册转换器。 */
	private boolean enabled = false;

	/** 应用启动时自动拉起子进程；false 则首次转换调用时懒启动。 */
	private boolean autoStart = true;

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public String getCommand() {
		return command;
	}

	public void setCommand(String command) {
		this.command = command;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isAutoStart() {
		return autoStart;
	}

	public void setAutoStart(boolean autoStart) {
		this.autoStart = autoStart;
	}

	/** MCP 客户端连接的 Streamable HTTP 端点（host 固定 localhost；带尾斜杠，markitdown-mcp 的
	 *  {@code /mcp} 会 307 重定向到 {@code /mcp/}，直接连尾斜杠端点避免重定向）。 */
	public String endpointUrl() {
		return "http://127.0.0.1:" + port + "/mcp/";
	}
}
