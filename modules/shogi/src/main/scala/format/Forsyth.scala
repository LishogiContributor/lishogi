package shogi
package format

import variant.{ Standard, Variant }

/** Transform a game to standard Forsyth Edwards Notation
  * http://en.wikipedia.org/wiki/Forsyth%E2%80%93Edwards_Notation
  *
  * Crazyhouse & Threecheck extensions:
  * https://github.com/ddugovic/Stockfish/wiki/FEN-extensions
  * http://scidb.sourceforge.net/help/en/FEN.html#ThreeCheck
  */
object Forsyth {

  val initial = "lnsgkgsnl/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGSNL b - 1"

  def <<@(variant: Variant, rawSource: String): Option[Situation] =
    read(rawSource) { fen =>
      makeBoard(variant, fen) map { board =>
        val splitted    = fen split ' '
        val colorOption = splitted lift 1 flatMap (_ lift 0) flatMap Color.apply
        val situation = colorOption match {
          case Some(color)            => Situation(board, color)
          case _ if board.check(Gote) => Situation(board, Gote) // user in check will move first
          case _                      => Situation(board, Sente)
        }
        situation withHistory {
          History(lastMove = None, positionHashes = Array.empty)
        }
      }
    }

  def <<(rawSource: String): Option[Situation] = <<@(Standard, rawSource)

  case class SituationPlus(situation: Situation, moveNumber: Int) {

    def turns = fullMoveNumber * 2 - (if (situation.color.sente) 2 else 1)

    def fullMoveNumber = 1 + (moveNumber - 1) / 2

  }

  def <<<@(variant: Variant, rawSource: String): Option[SituationPlus] =
    read(rawSource) { source =>
      <<@(variant, source) map { sit =>
        val splitted   = source.split(' ').drop(3)
        val moveNumber = splitted lift 0 flatMap parseIntOption map (_ max 1 min 500)
        SituationPlus(
          sit,
          moveNumber getOrElse 1
        )
      }
    }

  def <<<(rawSource: String): Option[SituationPlus] = <<<@(Standard, rawSource)

  def singleCharSfen(sfen: String): String =
    sfen
      .replaceAll("\\+S", "A")
      .replaceAll("\\+s", "a")
      .replaceAll("\\+N", "M")
      .replaceAll("\\+n", "m")
      .replaceAll("\\+L", "U")
      .replaceAll("\\+l", "u")
      .replaceAll("\\+P", "T")
      .replaceAll("\\+p", "t")
      .replaceAll("\\+R", "D")
      .replaceAll("\\+r", "d")
      .replaceAll("\\+B", "H")
      .replaceAll("\\+b", "h")

  def makeBoard(variant: Variant, rawSource: String): Option[Board] =
    read(rawSource) { fen =>
      val splitted  = fen.split(' ')
      val positions = singleCharSfen(splitted.lift(0).get)
      if (positions.count('/' ==) != 8) {
        return None
      }
      makePiecesList(positions.toList, 1, 9) map { case pieces =>
        val board = Board(pieces, variant)
        if (splitted.length < 3 || splitted.lift(2).get == "-") board
        else {
          val pockets       = pocketStringList(splitted.lift(2).get.toList.take(100))
          val (sente, gote) = pockets.flatMap(Piece.fromChar).partition(_ is Sente)
          import shogi.{ Data, Pocket, Pockets }
          board.withCrazyData(
            _.copy(
              pockets = Pockets(
                sente = Pocket(sente.map(_.role)),
                gote = Pocket(gote.map(_.role))
              )
            )
          )
        }
      }
    }

  private def pocketStringList(orig: List[Char], times: Int = 1, prev: Boolean = false): List[Char] = {
    orig match {
      case Nil                                       => Nil
      case _ if times > 50                           => Nil
      case c :: rest if prev && '0' <= c && c <= '9' => pocketStringList(rest, times * 10 + c.asDigit, true)
      case c :: rest if '1' <= c && c <= '9'         => pocketStringList(rest, c.asDigit, true)
      case c :: rest                                 => (c.toString * times).toList ::: pocketStringList(rest)
    }
  }

  private def makePiecesList(
      chars: List[Char],
      x: Int,
      y: Int
  ): Option[List[(Pos, Piece)]] =
    chars match {
      case Nil                               => Some(Nil)
      case '/' :: rest                       => makePiecesList(rest, 1, y - 1)
      case c :: rest if '1' <= c && c <= '9' => makePiecesList(rest, x + (c - '0').toInt, y)
      case c :: rest =>
        for {
          pos          <- Pos.posAt(x, y)
          piece        <- Piece.fromChar(c)
          (nextPieces) <- makePiecesList(rest, x + 1, y)
        } yield (pos -> piece :: nextPieces)
    }

  def >>(situation: Situation): String = >>(SituationPlus(situation, 1))

  def >>(parsed: SituationPlus): String =
    parsed match {
      case SituationPlus(situation, _) => >>(Game(situation, turns = parsed.turns))
    }

  def >>(game: Game): String =
    List(
      exportBoard(game.board),
      game.player.letter,
      exportCrazyPocket(game.board),
      game.moveNumber
    ) mkString " "

  def exportSituation(situation: Situation): String =
    List(
      exportBoard(situation.board),
      situation.color.letter,
      exportCrazyPocket(situation.board)
    ) mkString " "

  def exportCrazyPocket(board: Board) =
    board.crazyData match {
      case Some(shogi.Data(pockets)) => pockets.exportPockets
      case _                            => "-"
    }

  implicit private val posOrdering = Ordering.by[Pos, Int](_.x)

  def exportBoard(board: Board): String = {
    val fen   = new scala.collection.mutable.StringBuilder(256)
    var empty = 0
    for (y <- 9 to 1 by -1) {
      empty = 0
      for (x <- 1 to 9) {
        board(x, y) match {
          case None => empty = empty + 1
          case Some(piece) =>
            if (empty == 0) fen append piece.forsythFull
            else {
              fen append (empty.toString + piece.forsythFull)
              empty = 0
            }
        }
      }
      if (empty > 0) fen append empty
      if (y > 1) fen append '/'
    }
    fen.toString
  }

  def getMoveNumber(rawSource: String): Option[Int] =
    read(rawSource) { fen =>
      fen.split(' ').lift(3) flatMap parseIntOption
    }

  def getColor(rawSource: String): Option[Color] =
    read(rawSource) { fen =>
      fen.split(' ').lift(1) flatMap (_.headOption) flatMap Color.apply
    }

  def getPly(rawSource: String): Option[Int] =
    read(rawSource) { fen =>
      getMoveNumber(fen) map { moveNumber =>
        moveNumber - 1
      }
    }

  private def read[A](source: String)(f: String => A): A = f(source.replace("_", " ").trim)
}
