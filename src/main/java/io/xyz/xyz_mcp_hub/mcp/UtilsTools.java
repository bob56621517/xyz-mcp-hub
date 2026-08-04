package io.xyz.xyz_mcp_hub.mcp;

import java.time.Instant;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class UtilsTools {

	@Tool(description = "返回当前日期和时间")
	public String currentDateTime() {
		return Instant.now().toString();
	}
}
