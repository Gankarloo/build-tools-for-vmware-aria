/*-
 * #%L
 * vrotsc
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
import { Decorator, MethodDeclaration, SourceFile } from "typescript";
import { StringBuilderClass } from "../../../../../utilities/stringBuilder";
import { WorkflowItemDescriptor, WorkflowItemType } from "../../../../decorators";
import { getDecoratorProps } from "../../../helpers/node";
import CanvasItemDecoratorStrategy from "./canvasItemDecoratorStrategy";
import { buildItemParameterBindings, InputOutputBindings } from "./helpers/presentation";

// UI positioning constants in the output XML file.
const xBasePosition = 160;
const yBasePosition = 100;
const offSet = 20;

export default class EndItemDecoratorStrategy implements CanvasItemDecoratorStrategy {
	/**
	 * Return XML tag for the end workflow item.
	 * 
	 * @returns XML tag name.
	 */
	getCanvasType(): string {
		return "end";
	}

	/**
	 * Return the workflow item type supported by this decorator.
	 * 
	 * @returns type of the workflow element.
	 */
	getDecoratorType(): WorkflowItemType {
		return WorkflowItemType.End;
	}

	/**
	 * Register the canvas item arguments. For the default error handler only "endMode" and "exception" are supported.
	 * 
	 * @param itemInfo item info for that properties should be fetched.
	 * @param decoratorNode decorator node handle.
	 */
	registerItemArguments(itemInfo: WorkflowItemDescriptor, decoratorNode: Decorator): void {
		const decoratorProperties: [string, any][] = getDecoratorProps(decoratorNode);
		if (!decoratorProperties?.length) {
			return;
		}
		decoratorProperties.forEach((propTuple) => {
			const [propName, propValue] = propTuple;
			switch (propName) {
				case "endMode": {
					itemInfo.canvasItemPolymorphicBag.endMode = propValue;
					break;
				}
				case "exception": {
					itemInfo.canvasItemPolymorphicBag.exception = propValue;
					break;
				}
				case "businessStatus": {
					itemInfo.canvasItemPolymorphicBag.businessStatus = propValue;
					break;
				}
				default: {
					throw new Error(`Item attribute '${propName}' is not supported for ${this.getDecoratorType()} item`);
				}
			}
		});
	}

	/**
	 * There is no need to print the source file for the workflow item.
	 */
	printSourceFile(methodNode: MethodDeclaration, sourceFile: SourceFile): string {
		return "";
	}

	/**
	 * Prints out the end item.
	 *
	 * @param itemInfo The item to print.
	 * @param pos The position of the item in the workflow.
	 *
	 * @returns The string representation of the item.
	 */
	printItem(itemInfo: WorkflowItemDescriptor, pos: number): string {
		const stringBuilder = new StringBuilderClass("", "");

		const endMode = itemInfo?.canvasItemPolymorphicBag?.endMode;
		const exceptionVariable = itemInfo?.canvasItemPolymorphicBag?.exception;
		const businessStatus = itemInfo?.canvasItemPolymorphicBag?.businessStatus;
		const hasException = (exceptionVariable !== null && exceptionVariable !== undefined && exceptionVariable);
		const hasBusinessStatus = (businessStatus !== null && businessStatus !== undefined && businessStatus);

		stringBuilder.append(`<workflow-item name="item${pos}" type="end" end-mode="${endMode}" `);
		if (hasException) {
			stringBuilder.append(`throw-bind-name="${exceptionVariable}" `);
		}
		if (hasBusinessStatus) {
			stringBuilder.append(`business-status="${businessStatus}" `);
		}
		stringBuilder.append(">").appendLine();
		stringBuilder.indent();
		stringBuilder.appendContent(buildItemParameterBindings(itemInfo, InputOutputBindings.OUT_BINDINGS));
		stringBuilder.append(`<position x="${xBasePosition + offSet * (pos + 10)}" y="${yBasePosition}"/>`).appendLine();
		stringBuilder.unindent();
		stringBuilder.append(`</workflow-item>`).appendLine();

		return stringBuilder.toString();
	}
}
