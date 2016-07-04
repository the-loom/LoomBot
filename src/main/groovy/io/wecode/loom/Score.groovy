package io.wecode.loom

class Score {

    def value
    def total

    def plus(def that) {
        new Score(value: (this.value + that.value),
                total: (this.total + that.total)
        )
    }

    @Override
    String toString() {
        "${new BigDecimal(value).setScale(2, BigDecimal.ROUND_HALF_UP)}/${total}"
    }

    def normalize(def max = 10.0) {
        new Score(value: (this.value / this.total) * max,
                total: max
        )
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        Score score = (Score) o

        if (total != score.total) return false
        if (value != score.value) return false

        return true
    }

    int hashCode() {
        int result
        result = (value != null ? value.hashCode() : 0)
        result = 31 * result + (total != null ? total.hashCode() : 0)
        return result
    }
}
