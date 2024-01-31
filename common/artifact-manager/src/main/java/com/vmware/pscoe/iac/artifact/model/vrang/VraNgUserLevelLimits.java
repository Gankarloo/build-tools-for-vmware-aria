package com.vmware.pscoe.iac.artifact.model.vrang;

/*-
 * #%L
 * artifact-manager
 * %%
 * Copyright (C) 2023 - 2024 VMware
 * %%
 * Build Tools for VMware Aria
 * Copyright 2023 VMware, Inc.
 * 
 * This product is licensed to you under the BSD-2 license (the "License"). You may not use this product except in compliance with the BSD-2 License.
 * 
 * This product may include a number of subcomponents with separate copyright notices and license terms. Your use of these subcomponents is subject to the terms and conditions of the subcomponent's license, as noted in the LICENSE file.
 * #L%
 */

public class VraNgUserLevelLimits {
	public VraNgLimits getLimits() {
		return limits;
	}

	public void setLimits(VraNgLimits limits) {
		this.limits = limits;
	}

	private VraNgLimits limits;
	public VraNgUserLevelLimits(VraNgLimits limitsIn){
		this.limits  = limitsIn;
	}
}
