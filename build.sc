import mill._
import mill.scalalib._

trait SpinalModule extends ScalaModule {
  def scalaVersion = "2.13.14"

  def ivyDeps = Agg(
    ivy"com.github.spinalhdl::spinalhdl-core:1.10.2",
    ivy"com.github.spinalhdl::spinalhdl-lib:1.10.2"
  )

  def scalacPluginIvyDeps = Agg(
    ivy"com.github.spinalhdl::spinalhdl-idsl-plugin:1.10.2"
  )

  def scalacOptions: T[Seq[String]] = Seq("-deprecation")
}

object fpga extends SpinalModule {
  object sim extends SpinalModule {
    def moduleDeps = Seq(fpga)
  }
}
