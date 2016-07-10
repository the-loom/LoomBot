package io.wecode.loom

class CheckstyleIssue {

    String file
    Integer line
    Integer column = 0
    String message
    String severity

    @Override
    public String toString() {
        this.dump()
    }

    def toMap() {
        [
                message: message,
                payload: [
                        line: line,
                        column: column,
                        filename: file
                ],
                details: message,
                issue_type: severity
        ]

    }

}