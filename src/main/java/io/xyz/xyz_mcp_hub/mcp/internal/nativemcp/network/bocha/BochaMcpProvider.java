package io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.network.bocha;

import java.util.List;

import io.xyz.xyz_mcp_hub.mcp.Scope;
import io.xyz.xyz_mcp_hub.mcp.internal.nativemcp.NativeMcp;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * 博查搜索原生 MCP 端点提供者 —— 桩实现。
 *
 * <p>本期只确定包位置与端点路径（{@code /mcp/server/bocha}），工具列表为空；博查 API 的对接在后续工单中实现。</p>
 */
@Component
public class BochaMcpProvider extends NativeMcp {

	public BochaMcpProvider() {
		super(Scope.NETWORK);
	}

	@Override
	public String getName() {
		return "bocha";
	}

	@Override
	public String getPath() {
		return "/mcp/server/bocha";
	}

	@Override
	public List<ToolCallback> getTools() {
		return List.of();
	}

}
