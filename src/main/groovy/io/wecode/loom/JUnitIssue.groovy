package io.wecode.loom

import groovy.transform.EqualsAndHashCode

@EqualsAndHashCode
class JUnitIssue {

    String classname
    String test
    String message
    String details
    String type

    @Override
    public String toString() {
        //"[${type}] ${classname}:${test} -> '${message}'\n${details}\n"
        this.dump()
    }

}