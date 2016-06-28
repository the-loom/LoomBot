package io.wecode.loom

import groovy.transform.EqualsAndHashCode

@EqualsAndHashCode
class CheckstyleIssue {

    String file
    Integer line
    String message

    @Override
    public String toString() {
        this.dump()
    }

}