namespace vrotsc {
    export function printElementInfo(properties: Record<string, string>): string {
        const stringBuilder = createStringBuilder();
        stringBuilder.append(`<?xml version="1.0" encoding="utf-8" standalone="no"?>`).appendLine();
        stringBuilder.append(`<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">`).appendLine();
        stringBuilder.append(`<properties>`).appendLine();
        stringBuilder.append(`<comment>Generated by vrotsc</comment>`).appendLine();
        Object.keys(properties).forEach(key => {
            stringBuilder.append(`<entry key="${key}">${properties[key]}</entry>`).appendLine();
        });
        stringBuilder.append(`</properties>`).appendLine();
        return stringBuilder.toString();
    }
}