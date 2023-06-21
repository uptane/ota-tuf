import $ivy.`org.mariadb.jdbc:mariadb-java-client:2.7.4`
import $ivy.`com.lihaoyi::ammonite-ops:2.4.0`

import ammonite.ops._

import scala.concurrent.duration.Duration

@main
def main(url: String, file: String) = {
  val json: String = read! pwd/file

  import java.sql.Connection
  import java.sql.DriverManager
  val con = DriverManager.getConnection(url)

  val del = "delete from signed_roles where repo_id = '48c7372d-95aa-4dca-aafe-60b30a014dcc' limit 1 ;"

  con.createStatement().execute(del)

  val stm = con.prepareStatement("INSERT INTO signed_roles (repo_id, role_type, checksum, `length`, version, content) values (?, ?, ?, ?, ?,?)")

  stm.setString(1, "48c7372d-95aa-4dca-aafe-60b30a014dcc")
  stm.setString(2, "TARGETS")
  stm.setString(3, "x")
  stm.setLong(4, 0)
  stm.setLong(5, -1)
  stm.setString(6, json)

  val startAt = System.nanoTime()

  val res = stm.executeUpdate()

  val took = Duration.fromNanos(System.nanoTime() - startAt)

  println(s"Finished: $res took ${took.toMillis}ms")
}
