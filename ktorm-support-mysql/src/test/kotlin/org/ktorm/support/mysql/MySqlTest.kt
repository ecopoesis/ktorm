package org.ktorm.support.mysql

import org.junit.ClassRule
import org.junit.Test
import org.ktorm.BaseTest
import org.ktorm.database.Database
import org.ktorm.database.use
import org.ktorm.dsl.*
import org.ktorm.entity.*
import org.ktorm.jackson.json
import org.ktorm.logging.ConsoleLogger
import org.ktorm.logging.LogLevel
import org.ktorm.schema.Table
import org.ktorm.schema.int
import org.ktorm.schema.varchar
import org.testcontainers.containers.MySQLContainer
import java.time.LocalDate
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Created by vince on Dec 12, 2018.
 */
class MySqlTest : BaseTest() {

    companion object {
        const val TOTAL_RECORDS = 4
        const val MINUS_ONE = -1
        const val ZERO = 0
        const val ONE = 1
        const val TWO = 2
        const val ID_1 = 1
        const val ID_2 = 2
        const val ID_3 = 3
        const val ID_4 = 4

        class KMySqlContainer : MySQLContainer<KMySqlContainer>("mysql:8")

        @ClassRule
        @JvmField
        val mysql = KMySqlContainer()
    }

    override fun init() {
        database = Database.connect(
            url = mysql.jdbcUrl,
            driver = mysql.driverClassName,
            user = mysql.username,
            password = mysql.password,
            logger = ConsoleLogger(threshold = LogLevel.TRACE)
        )

        execSqlScript("init-mysql-data.sql")
    }

    override fun destroy() {
        execSqlScript("drop-mysql-data.sql")
    }

    @Test
    fun testKeywordWrapping() {
        val configs = object : Table<Nothing>("t_config") {
            val key = varchar("key").primaryKey()
            val value = varchar("value")
        }

        database.useConnection { conn ->
            conn.createStatement().use { statement ->
                val sql = """create table t_config(`key` varchar(128) primary key, `value` varchar(128))"""
                statement.executeUpdate(sql)
            }
        }

        database.insert(configs) {
            set(it.key, "test")
            set(it.value, "test value")
        }

        assert(database.sequenceOf(configs).count { it.key eq "test" } == 1)

        database.delete(configs) { it.key eq "test" }
    }

    @Test
    fun testLimit() {
        val query = database.from(Employees).select().orderBy(Employees.id.desc()).limit(0, 2)
        assert(query.totalRecords == 4)

        val ids = query.map { it[Employees.id] }
        assert(ids.size == 2)
        assert(ids[0] == 4)
        assert(ids[1] == 3)
    }

    /**
     * Verifies that invalid pagination parameters are ignored.
     */
    @Test
    fun testBothLimitAndOffsetAreNotPositive() {
        val query = database.from(Employees).select().orderBy(Employees.id.desc()).limit(ZERO, MINUS_ONE)
        assert(query.totalRecords == TOTAL_RECORDS)

        val ids = query.map { it[Employees.id] }
        assert(ids == listOf(ID_4, ID_3, ID_2, ID_1))
    }

    /**
     * Verifies that limit parameter works as expected.
     */
    @Test
    fun testLimitWithoutOffset() {
        val query = database.from(Employees).select().orderBy(Employees.id.desc()).limit(TWO)
        assert(query.totalRecords == TOTAL_RECORDS)

        val ids = query.map { it[Employees.id] }
        assert(ids == listOf(ID_4, ID_3))
    }

    /**
     * Verifies that offset parameter works as expected.
     */
    @Test
    fun testOffsetWithoutLimit() {
        val query = database.from(Employees).select().orderBy(Employees.id.desc()).offset(TWO)
        assert(query.totalRecords == TOTAL_RECORDS)

        val ids = query.map { it[Employees.id] }
        assert(ids == listOf(ID_2, ID_1))
    }

    /**
     * Verifies that limit and offset parameters work together as expected.
     */
    @Test
    fun testOffsetWithLimit() {
        val query = database.from(Employees).select().orderBy(Employees.id.desc()).offset(TWO).limit(ONE)
        assert(query.totalRecords == TOTAL_RECORDS)

        val ids = query.map { it[Employees.id] }
        assert(ids == listOf(ID_2))
    }

    @Test
    fun testInsertOrUpdate() {
        database.insertOrUpdate(Employees) {
            set(it.id, 1)
            set(it.name, "vince")
            set(it.job, "engineer")
            set(it.salary, 1000)
            set(it.hireDate, LocalDate.now())
            set(it.departmentId, 1)
            onDuplicateKey {
                set(it.salary, it.salary + 1000)
            }
        }
        database.insertOrUpdate(Employees.aliased("t")) {
            set(it.id, 5)
            set(it.name, "vince")
            set(it.job, "engineer")
            set(it.salary, 1000)
            set(it.hireDate, LocalDate.now())
            set(it.departmentId, 1)
            onDuplicateKey {
                set(it.salary, it.salary + 1000)
            }
        }

        assert(database.employees.find { it.id eq 1 }!!.salary == 1100L)
        assert(database.employees.find { it.id eq 5 }!!.salary == 1000L)
    }

    @Test
    fun testBulkInsert() {
        database.bulkInsert(Employees) {
            item {
                set(it.name, "vince")
                set(it.job, "engineer")
                set(it.salary, 1000)
                set(it.hireDate, LocalDate.now())
                set(it.departmentId, 1)
            }
            item {
                set(it.name, "vince")
                set(it.job, "engineer")
                set(it.salary, 1000)
                set(it.hireDate, LocalDate.now())
                set(it.departmentId, 1)
            }
        }

        assert(database.employees.count() == 6)
    }

    @Test
    fun testBulkInsertOrUpdate() {
        database.bulkInsertOrUpdate(Employees) {
            item {
                set(it.id, 1)
                set(it.name, "vince")
                set(it.job, "trainee")
                set(it.salary, 1000)
                set(it.hireDate, LocalDate.now())
                set(it.departmentId, 2)
            }
            item {
                set(it.id, 5)
                set(it.name, "vince")
                set(it.job, "engineer")
                set(it.salary, 1000)
                set(it.hireDate, LocalDate.now())
                set(it.departmentId, 2)
            }
            onDuplicateKey {
                set(it.job, it.job)
                set(it.departmentId, values(it.departmentId))
                set(it.salary, it.salary + 1000)
            }
        }

        database.employees.find { it.id eq 1 }!!.let {
            assert(it.job == "engineer")
            assert(it.department.id == 2)
            assert(it.salary == 1100L)
        }

        database.employees.find { it.id eq 5 }!!.let {
            assert(it.job == "engineer")
            assert(it.department.id == 2)
            assert(it.salary == 1000L)
        }
    }

    @Test
    fun testNaturalJoin() {
        val query = database.from(Employees).naturalJoin(Departments).select()
        assert(query.rowSet.size() == 0)
    }

    @Test
    fun testPagingSql() {
        var query = database
            .from(Employees)
            .leftJoin(Departments, on = Employees.departmentId eq Departments.id)
            .select()
            .orderBy(Departments.id.desc())
            .limit(0, 1)

        assert(query.totalRecords == 4)

        query = database
            .from(Employees)
            .select(Employees.name)
            .orderBy((Employees.id + 1).desc())
            .limit(0, 1)

        assert(query.totalRecords == 4)

        query = database
            .from(Employees)
            .select(Employees.departmentId, avg(Employees.salary))
            .groupBy(Employees.departmentId)
            .limit(0, 1)

        assert(query.totalRecords == 2)

        query = database
            .from(Employees)
            .selectDistinct(Employees.departmentId)
            .limit(0, 1)

        assert(query.totalRecords == 2)

        query = database
            .from(Employees)
            .select(max(Employees.salary))
            .limit(0, 1)

        assert(query.totalRecords == 1)

        query = database
            .from(Employees)
            .select(Employees.name)
            .limit(0, 1)

        assert(query.totalRecords == 4)
    }

    @Test
    fun testDrop() {
        val employees = database.employees.drop(1).drop(1).drop(1).toList()
        assert(employees.size == 1)
        assert(employees[0].name == "penny")
    }

    @Test
    fun testTake() {
        val employees = database.employees.take(2).take(1).toList()
        assert(employees.size == 1)
        assert(employees[0].name == "vince")
    }

    @Test
    fun testElementAt() {
        val employee = database.employees.drop(2).elementAt(1)

        assert(employee.name == "penny")
        assert(database.employees.elementAtOrNull(4) == null)
    }

    @Test
    fun testMapColumns3() {
        database.employees
            .filter { it.departmentId eq 1 }
            .mapColumns { tupleOf(it.id, it.name, dateDiff(LocalDate.now(), it.hireDate)) }
            .forEach { (id, name, days) ->
                println("$id:$name:$days")
            }
    }

    @Test
    fun testMatchAgainst() {
        val employees = database.employees.filterTo(ArrayList()) {
            match(it.name, it.job).against("vince", SearchModifier.IN_NATURAL_LANGUAGE_MODE)
        }

        employees.forEach { println(it) }
    }

    @Test
    fun testReplace() {
        val names = database.employees.mapColumns { it.name.replace("vince", "VINCE") }
        println(names)
    }

    @Test
    fun testInsertAndGenerateKey() {
        val id = database.insertAndGenerateKey(Employees) {
            set(it.name, "Joe Friend")
            set(it.job, "Tester")
            set(it.managerId, null)
            set(it.salary, 50)
            set(it.hireDate, LocalDate.of(2020, 1, 10))
            set(it.departmentId, 1)
        } as Int

        assert(id > 4)

        assert(database.employees.count() == 5)
    }

    @Test
    fun testToUpperCase() {
        val name = database.employees
            .filter { it.id eq 1 }
            .mapColumns { it.name.toUpperCase() }
            .first()

        assert(name == "VINCE")
    }

    @Test
    fun testIf() {
        val countRich = database
            .from(Employees)
            .select(sum(IF(Employees.salary greaterEq 100L, 1, 0)))
            .map { row -> row.getInt(1) }

        assert(countRich.size == 1)
        assert(countRich.first() == 3)
    }

    @Test
    fun testSum() {
        val countRich = database
            .from(Employees)
            .select(sum(Employees.salary.greaterEq(100L).toInt()))
            .map { row -> row.getInt(1) }

        assert(countRich.size == 1)
        assert(countRich.first() == 3)
    }

    @Test
    fun testJson() {
        val t = object : Table<Nothing>("t_json") {
            val obj = json<Employee>("obj")
            val arr = json<List<Int>>("arr")
        }

        database.useConnection { conn ->
            conn.createStatement().use { statement ->
                val sql = """create table t_json (obj text, arr text)"""
                statement.executeUpdate(sql)
            }
        }

        database.insert(t) {
            set(it.obj, Employee { name = "vince"; salary = 100 })
            set(it.arr, listOf(1, 2, 3))
        }

        database
            .from(t)
            .select(t.obj, t.arr)
            .forEach { row ->
                println("${row.getString(1)}:${row.getString(2)}")
            }

        database
            .from(t)
            .select(t.obj.jsonExtract<Long>("$.salary"), t.arr.jsonContains(0))
            .forEach { row ->
                println("${row.getLong(1)}:${row.getBoolean(2)}")
            }
    }

    @Test
    fun testSelectForUpdate() {
        database.useTransaction {
            val employee = database
                .sequenceOf(Employees, withReferences = false)
                .filter { it.id eq 1 }
                .forUpdate(MySqlForUpdateOption.ForUpdate)
                .first()

            val future = Executors.newSingleThreadExecutor().submit {
                employee.name = "vince"
                employee.flushChanges()
            }

            try {
                future.get(5, TimeUnit.SECONDS)
                throw AssertionError()
            } catch (e: ExecutionException) {
                // Expected, the record is locked.
                e.printStackTrace()
            } catch (e: TimeoutException) {
                // Expected, the record is locked.
                e.printStackTrace()
            }
        }
    }

    @Test
    fun testSchema() {
        val t = object : Table<Department>("t_department", schema = mysql.databaseName) {
            val id = int("id").primaryKey().bindTo { it.id }
            val name = varchar("name").bindTo { it.name }
        }

        database.update(t) {
            set(it.name, "test")
            where {
                it.id eq 1
            }
        }

        assert(database.sequenceOf(t).filter { it.id eq 1 }.mapTo(HashSet()) { it.name } == setOf("test"))
        assert(database.sequenceOf(t.aliased("t")).mapTo(HashSet()) { it.name } == setOf("test", "finance"))
    }

    @Test
    fun testMaxColumnNameLength() {
        val t = object : Table<Nothing>("t_long_name") {
            val col = varchar("a".repeat(database.maxColumnNameLength))
        }

        database.useConnection { conn ->
            conn.createStatement().use { statement ->
                val sql = """create table t_long_name(${t.col.name} varchar(128))"""
                statement.executeUpdate(sql)
            }
        }

        database.insert(t) {
            set(it.col, "test")
        }

        val name = database.from(t).select(t.col).map { it[t.col] }.first()
        assert(name == "test")
    }
}
