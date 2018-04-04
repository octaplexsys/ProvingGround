package provingground.interface
import provingground._, translation._
import induction._
import HoTT.{Name => _, _}

import trepplein._

import scala.meta.{Term => _, Type => _, _}
import ammonite.ops._

case class LeanCodeGen(parser: LeanParser){
  import parser._

  val base = pwd / "leanlib" / "src"

  val header = // just write this to a file
"""package provingground.library
import provingground._
import HoTT._
import induction._
import implicits._
import shapeless._
import Fold._ // for safety
"""

  def writeDefn(name: trepplein.Name, code: meta.Term) = {
    val obj = CodeGen.mkObject(name.toString, code)
    val file = base / "definitions" / s"$name.scala"
    write.over(file, header)

    write.append(file, obj.toString + "\n")
  }

  def writeInduc(name: trepplein.Name, ind: TermIndMod) = {
    val code = codeFromInd(ind)
    val obj = CodeGen.mkObject(s"${name}Ind", code)
    val file = base / "inductive-types" / s"${name}Ind.scala"
    write.over(file, header)

    write.append(file, obj.toString + "\n")
  }

  def save() = {
    defnCode.foreach{case (name, code) => writeDefn(name, code)}
    termIndModMap.foreach{case (name, ind) => writeInduc(name, ind)}
  }


}
