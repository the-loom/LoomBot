package io.wecode.loom

class JUnitIssue {

    String classname
    String test
    String message
    String details
    String type

    @Override
    public String toString() {
        this.dump()
    }

}