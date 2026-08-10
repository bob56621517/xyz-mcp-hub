package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.host;

import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.SourceType;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.NativeMcp;

/**
 * 主机 MCP 服务基类 —— 预留扩展点。
 *
 * <p>必须与 Agent/CLI 部署在同一主机上的服务（如文件系统操作）应继承此类，其 {@link Scope} 恒为
 * {@link Scope#HOST}。本期仅建立类型层级，不实现"非本机则拒绝"的运行时检查。</p>
 */
public abstract class HostMcp extends NativeMcp {

	protected HostMcp() {
		super(Scope.HOST);
	}

	/**
	 * 源类型（目录 API，issue #34）：主机 MCP 恒为 {@link SourceType#HOST}。
	 */
	@Override
	public final SourceType getSourceType() {
		return SourceType.HOST;
	}

}
