import { PolyglotDescriptor } from "../../../decorators";
import { getIdentifierTextOrNull, getPropertyName } from "../../helpers/node";

import * as ts from "typescript";

export function registerPolyglotDecorators(methodNode: ts.MethodDeclaration, polyglotInfo: PolyglotDescriptor) {

	let decoratorPolyglot = false;
	const decorators = ts.getDecorators(methodNode);
	if (decorators && decorators.length >= 1) {

		decorators.forEach(decoratorNode => {
			const callExpNode = decoratorNode.expression as ts.CallExpression;

			const identifierText = getIdentifierTextOrNull(callExpNode.expression);

			if (identifierText === "Polyglot") {
				decoratorPolyglot = true;

				//Extract the information stored in the @Polyglot decorator
				buildPolyglotInfo(polyglotInfo, callExpNode);
			}
		});
	}

	return decoratorPolyglot;
}

/**
 * Contains code related to workflow polyglot logic.
 */
export function buildPolyglotInfo(polyglotInfo: PolyglotDescriptor, decoratorCallExp: ts.CallExpression) {
	const objLiteralNode = decoratorCallExp.arguments[0] as ts.ObjectLiteralExpression;
	if (objLiteralNode) {
		objLiteralNode.properties.forEach((property: ts.PropertyAssignment) => {
			const propName = getPropertyName(property.name);
			switch (propName) {
				case "package":
					polyglotInfo.package = (<ts.StringLiteral>property.initializer).text;

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
					break;
				case "method":
					polyglotInfo.method = (<ts.StringLiteral>property.initializer).text;
					break;
				default:
					throw new Error(`Polyglot attribute '${propName}' is not suported.`);
			}
		});
	}
}

export function printPolyglotCode(moduleName: string, methodName: string, input: string[], output: string[]): string {
	let result = "";
	result += `//AUTOGENERATED POLYGLOT INVOCATION ---- DON'T TOUCHE THIS SECTION CODE\n`;
	result += `System.log("Starting execution of polyglot decorator")\n`;
	result += `var polyglot_module = System.getModule("${moduleName}");`;
	result += `var polyglot_result = polyglot_module["${methodName}"](${buildPolyglotInputArgs(input)});\n`;
	result += `${buildOutput(output)} = polyglot_result;\n`;
	result += `System.log("Finish execution of polyglot decorator")\n`;
	result += `System.log("Starting execution of action code")\n`;

	return result;
}

export function buildPolyglotInputArgs(input: string[]): string {
	let inputArgs = "";
	{
		for (let i = 0; i < input.length; i += 1) {
			inputArgs += `${input[i]},`;
		}
	}
	return inputArgs.substring(0, inputArgs.length - 1);//Remove the last ','
}

function buildOutput(output: string[]): string {
	if (output == undefined || output.length == 0) {
		throw new Error("Polyglot decorator require an @Out parameter");
	}
	return output[0];
}
