package io.wecode.loom

class CheckstyleIssue {

    String file
    Integer line
    String message

    @Override
    public String toString() {
        this.dump()
    }

}