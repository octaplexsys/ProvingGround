package provingground.interface
import provingground._

// import ammonite.ops._
// import scala.util._

import scala.concurrent._
import monix.execution.Scheduler.Implicits.global
import monix.eval._

import HoTT.{Name => _, _}

import trepplein._

// import cats.Eval

// import LeanToTerm.isPropn

// import translation.FansiShow._

object LeanToTermMonix {

  val proofLift: (Term, Term) => Task[Term] = {
    case (w: Typ[u], tp: Typ[v]) => Task { (w.Var) :-> tp }
    case (w: FuncLike[u, v], tp: FuncLike[a, b]) if w.dom == tp.dom =>
      val x = w.dom.Var
      proofLift(w(x), tp(x.asInstanceOf[a]))
        .map((g: Term) => x :~> (g: Term))
    case _ => Task.raiseError(new Exception("could not lift proof"))
  }

  type TaskParser = (Expr, Vector[Term]) => Task[Term]
}

case class LeanToTermMonix(defnMap: Map[Name, Term],
                           termIndModMap: Map[Name, TermIndMod],
                           unparsed: Vector[Name]) { self =>
  import LeanToTermMonix._

  def defnOpt(exp: Expr) =
    exp match {
      case Const(name, _) => defnMap.get(name)
      case _              => None
    }

  object Predef {
    def unapply(exp: Expr): Option[Term] =
      (
        defnOpt(exp)
        // .orElse(recDefns(parse)(exp))
      )
  }

  object RecIterAp {
    def unapply(exp: Expr): Option[(Name, Vector[Expr])] = exp match {
      case Const(Name.Str(prefix, "rec"), _) => Some((prefix, Vector()))
      case App(func, arg) =>
        unapply(func).map { case (name, vec) => (name, vec :+ arg) }
      case _ => None
    }
  }

  val inPropFamily: Term => Boolean = {
    case FormalAppln(f, _) => inPropFamily(f)
    case s: Symbolic =>
      val name = trepplein.Name(s.name.toString.split('.'): _*)
      termIndModMap.get(name).map(_.isPropn).getOrElse(false)
    case _ => false
  }

  def applyFuncProp(func: Term, arg: Term): Term =
    applyFuncOpt(func, arg).getOrElse {
      if (inPropFamily(arg.typ)) func
      else throw new ApplnFailException(func, arg)
    }

  val parse: TaskParser = recParser(parse)

  def recParser(rec: => TaskParser)(exp: Expr,
                                    vars: Vector[Term]): Task[Term] =
    exp match {
      case Predef(t) => Task.now(t)
      case Sort(_)   => Task.now(Type)
      case Var(n)    => Task.now(vars(n))
      case RecIterAp(name, args) =>
        val indMod           = termIndModMap(name)
        val (argsFmly, xs)   = args.splitAt(indMod.numParams + 1)
        val argsFmlyTermTask = parseVec(argsFmly, vars)
        val recFnTry: Task[Term] =
          argsFmlyTermTask.map { (vec) =>
            indMod.getRecOpt(Option(vec)).get
          }
        for {
          recFn <- recFnTry
          vec   <- parseVec(xs, vars)
          resTask = Task(vec.foldLeft(recFn)(applyFuncProp(_, _)))
          res <- resTask
        } yield res

      case App(f, a) =>
        Task.zipMap2(rec(f, vars), rec(a, vars))(applyFuncProp)
      case Lam(domain, body) =>
        for {
          domTerm <- rec(domain.ty, vars)
          domTyp  <- Task(toTyp(domTerm))
          x = domTyp.Var
          value <- rec(body, x +: vars)
        } yield
          value match {
            case FormalAppln(fn, arg) if arg == x && fn.indepOf(x) => fn
            case y if domain.prettyName.toString == "_"            => y
            case _ =>
              if (value.typ.dependsOn(x)) LambdaTerm(x, value)
              else LambdaFixed(x, value)
          }
      case Pi(domain, body) =>
        for {
          domTerm <- rec(domain.ty, vars)
          domTyp  <- Task(toTyp(domTerm))
          x = domTyp.Var
          value <- rec(body, x +: vars)
          cod   <- Task(toTyp(value))
          dep = cod.dependsOn(x)
        } yield if (dep) PiDefn(x, cod) else x.typ ->: cod
      case Let(domain, value, body) =>
        for {
          domTerm <- rec(domain.ty, vars)
          domTyp  <- Task(toTyp(domTerm))
          x = domTyp.Var
          valueTerm <- rec(value, vars)
          bodyTerm  <- rec(body, x +: vars)
        } yield bodyTerm.replace(x, valueTerm)
      case e => Task.raiseError(UnParsedException(e))
    }

  def parseTyp(x: Expr, vars: Vector[Term]): Task[Typ[Term]] =
    parse(x, vars).flatMap {
      case tp: Typ[_] => Task.now(tp)
      case t          =>
        // println(
        //   s"got term $t of type ${t.typ} but expected type when parsing $x")
        Task.raiseError(NotTypeException(t))
    }

  def parseVec(vec: Vector[Expr], vars: Vector[Term]): Task[Vector[Term]] =
    vec match {
      case Vector() => Task.now(Vector())
      case x +: ys =>
        for {
          head <- parse(x, vars)
          tail <- parseVec(ys, vars)
        } yield head +: tail
    }

  def parseTypVec(vec: Vector[Expr],
                  vars: Vector[Term]): Task[Vector[Typ[Term]]] = vec match {
    case Vector() => Task.now(Vector())
    case x +: ys =>
      for {
        head <- parseTyp(x, vars)
        tail <- parseTypVec(ys, vars)
      } yield head +: tail
  }

  def parseSymVec(vec: Vector[(Name, Expr)],
                  vars: Vector[Term]): Task[Vector[Term]] = vec match {
    case Vector() => Task.now(Vector())
    case (name, expr) +: ys =>
      for {
        tp <- parseTyp(expr, vars)
        head = name.toString :: tp
        tail <- parseSymVec(ys, vars)
      } yield head +: tail
  }

  def parseSym(name: Name, ty: Expr, vars: Vector[Term]) =
    parseTyp(ty, vars).map(name.toString :: _)

  def parseVar(b: Binding, vars: Vector[Term]) =
    parseSym(b.prettyName, b.ty, vars)

  def addDefnMap(name: Name, term: Term) =
    self.copy(defnMap = self.defnMap + (name -> term))

  def addDefnVal(name: Name, value: Expr, tp: Expr) =
    parse(value, Vector())
      .map((t) => addDefnMap(name, t))

  def addAxiom(name: Name, ty: Expr) =
    parseSym(name, ty, Vector())
      .map(addDefnMap(name, _))

  def addAxioms(axs: Vector[(Name, Expr)]) = {
    val taskVec = axs.map {
      case (name, ty) => parseSym(name, ty, Vector()).map((t) => (name, t))
    }
    val mvec = Task.gather(taskVec)
    mvec.map((vec) => self.copy(defnMap = self.defnMap ++ vec))
  }

  def addAxiomMod(ax: AxiomMod): Task[LeanToTermMonix] =
    addAxiom(ax.name, ax.ax.ty)

  def addDefMod(df: DefMod): Task[LeanToTermMonix] =
    addDefnVal(df.name, df.defn.value, df.defn.ty)

  def addQuotMod: Task[LeanToTermMonix] = {
    import quotient._
    val axs = Vector(quot, quotLift, quotMk, quotInd).map { (ax) =>
      (ax.name, ax.ty)
    }
    addAxioms(axs)
  }

  def addIndMod(ind: IndMod): Task[LeanToTermMonix] = {
    val withTypDef = addAxiom(ind.name, ind.inductiveType.ty)
    val withAxioms = withTypDef.flatMap(_.addAxioms(ind.intros))
    val indOpt: Task[TermIndMod] = {
      val inductiveTypOpt = parseTyp(ind.inductiveType.ty, Vector())
      val isPropn         = LeanToTerm.isPropn(ind.inductiveType.ty)
      inductiveTypOpt.flatMap { (inductiveTyp) =>
        val name = ind.inductiveType.name
        val typF = name.toString :: inductiveTyp
        val typValueOpt =
          Task.fromTry(LeanToTerm.getValue(typF, ind.numParams, Vector()))
        val introsTry =
          withTypDef.flatMap(_.parseSymVec(ind.intros, Vector()))
        introsTry.flatMap { (intros) =>
          typValueOpt.map { (typValue) =>
            typValue match {
              case (typ: Typ[Term], params) =>
                SimpleIndMod(ind.inductiveType.name,
                             typF,
                             intros,
                             params.size,
                             isPropn)
              case (t, params) =>
                IndexedIndMod(ind.inductiveType.name,
                              typF,
                              intros,
                              params.size,
                              isPropn)
            }
          }
        }
      }
    }
    indOpt
      .flatMap { (indMod) =>
        withAxioms.map(
          _.copy(termIndModMap = self.termIndModMap + (ind.name -> indMod)))
      }
  }

  def add(mod: Modification): Task[LeanToTermMonix] = mod match {
    case ind: IndMod  => addIndMod(ind)
    case ax: AxiomMod => addAxiomMod(ax)
    case df: DefMod   => addDefMod(df)
    case QuotMod      => addQuotMod
  }

}
