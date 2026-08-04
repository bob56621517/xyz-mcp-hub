package io.xyz.xyz_mcp_hub.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfig {

	@Bean
	public ToolCallbackProvider utilsToolCallbacks(UtilsTools utilsTools) {
		return MethodToolCallbackProvider.builder().toolObjects(utilsTools).build();
	}
}
