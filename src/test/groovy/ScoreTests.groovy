import io.wecode.loom.Score

import static org.junit.Assert.*

import org.junit.Test

class ScoreTests {

    @Test void thatCanNormalizeScore() {
        def score = new Score(value: 3, total: 5)
        assertEquals new Score(value: 6, total: 10), score.normalize(10)
    }

    @Test void thatCanAddTwoScores() {
        def score1 = new Score(value: 3, total: 5)
        def score2 = new Score(value: 2, total: 2)

        assertEquals new Score(value: 5, total: 7), score1 + score2
    }

}
