package app.bizdrivelog.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.DriverManager

/** 걸러진 주차 조작은 번호를 쓰지 않는다. TripDao의 실제 SQL을 SQLite에서 실행해 확인 */
class TripNumberTest {

    private val db = DriverManager.getConnection("jdbc:sqlite::memory:").also {
        it.createStatement().execute("CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT, endedAt INTEGER, confirmedAt INTEGER)")
    }

    @After fun close() = db.close()

    private fun start(): Long {
        db.createStatement().execute("INSERT INTO trips (endedAt) VALUES (NULL)")
        return db.createStatement().executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) }
    }

    private fun end(id: Long) {
        db.createStatement().execute("UPDATE trips SET endedAt = 1 WHERE id = $id")
    }

    private fun discard(id: Long) {
        db.createStatement().execute(DELETE_OPEN.replace(":id", id.toString()))
        db.createStatement().execute(REWIND_NUMBER)
    }

    private fun ids(): List<Long> = db.createStatement().executeQuery("SELECT id FROM trips ORDER BY id").use { r ->
        buildList { while (r.next()) add(r.getLong(1)) }
    }

    @Test fun `버린 운행 다음 운행이 그 번호를 쓴다`() {
        end(start())
        discard(start())
        discard(start())
        end(start())
        assertEquals(listOf(1L, 2L), ids())
    }

    @Test fun `첫 운행을 버려도 1번부터 시작한다`() {
        discard(start())
        assertEquals(1L, start())
    }

    @Test fun `끝난 운행은 지워지지 않고 번호도 되돌리지 않는다`() {
        val first = start()
        end(first)
        discard(first)
        assertEquals(listOf(first), ids())
        assertEquals(first + 1, start())
    }
}
