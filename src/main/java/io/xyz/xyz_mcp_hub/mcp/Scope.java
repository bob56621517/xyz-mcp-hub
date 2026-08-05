package io.xyz.xyz_mcp_hub.mcp;

/**
 * MCP 端点的部署范围。
 *
 * <ul>
 * <li>{@link #HOST} — 必须与 Agent/CLI 部署在同一主机上的服务（如文件系统操作）。当前仅作预留标记，不实现运行时检查。</li>
 * <li>{@link #NETWORK} — 通过网络可达即可，对部署位置无约束。</li>
 * </ul>
 */
public enum Scope {

	HOST,
	NETWORK

}
