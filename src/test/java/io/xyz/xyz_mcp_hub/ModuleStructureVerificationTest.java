package io.xyz.xyz_mcp_hub;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Spring Modulith 模块结构验证：直接子包为应用模块，嵌套子包自动视为内部实现，不得被跨模块引用。
 */
class ModuleStructureVerificationTest {

	@Test
	void verifyModuleStructure() {
		ApplicationModules.of(XyzMcpHubApplication.class).verify();
	}

}
