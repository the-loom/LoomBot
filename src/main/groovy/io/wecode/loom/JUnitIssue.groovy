package io.wecode.loom

class JUnitIssue {

    String classname
    String test
    String message
    String details
    String type
    Double time

    @Override
    public String toString() {
        this.dump()
    }

    def toMap() {
        [
                message   : message,
                payload   : [
                        test_class: classname,
                        test_name : test,
                        test_time : time
                ],
                details   : details,
                issue_type: type
        ]

    }

}