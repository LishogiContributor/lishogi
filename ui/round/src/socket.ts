import notify from 'common/notification';
import throttle from 'common/throttle';
import * as game from 'game';
import { isPlayerTurn } from 'game';
import RoundController from './ctrl';
import { Untyped } from './interfaces';
import * as sound from './sound';
import * as xhr from './xhr';

const li = window.lishogi;

export interface RoundSocket extends Untyped {
  send: SocketSend;
  handlers: Untyped;
  moreTime(): void;
  outoftime(): void;
  berserk(): void;
  sendLoading(typ: string, data?: any): void;
  receive(typ: string, data: any): boolean;
}

interface Incoming {
  t: string;
  d: any;
}

interface Handlers {
  [key: string]: (data: any) => void;
}

type Callback = (...args: any[]) => void;

function backoff(delay: number, factor: number, callback: Callback): Callback {
  let timer: number | undefined;
  let lastExec = 0;

  return function (this: any, ...args: any[]): void {
    const self: any = this;
    const elapsed = performance.now() - lastExec;

    function exec() {
      timer = undefined;
      lastExec = performance.now();
      delay *= factor;
      callback.apply(self, args);
    }

    if (timer) clearTimeout(timer);

    if (elapsed > delay) exec();
    else timer = setTimeout(exec, delay - elapsed);
  };
}

export function make(send: SocketSend, ctrl: RoundController): RoundSocket {
  function reload(o: Incoming, isRetry?: boolean) {
    // avoid reload if possible!
    if (o && o.t) {
      ctrl.setLoading(false);
      handlers[o.t](o.d);
    } else
      xhr.reload(ctrl).then(data => {
        if (li.socket.getVersion() > data.player.version) {
          // race condition! try to reload again
          if (isRetry) li.reload();
          // give up and reload the page
          else reload(o, true);
        } else ctrl.reload(data);
      });
  }

  const handlers: Handlers = {
    takebackOffers(o) {
      ctrl.setLoading(false);
      ctrl.data.player.proposingTakeback = o[ctrl.data.player.color];
      const fromOp = (ctrl.data.opponent.proposingTakeback = o[ctrl.data.opponent.color]);
      if (fromOp) notify(ctrl.noarg('yourOpponentProposesATakeback'));
      ctrl.redraw();
    },
    usi: ctrl.apiMove,
    reload,
    redirect: ctrl.setRedirecting,
    clockInc(o) {
      if (ctrl.clock) {
        ctrl.clock.addTime(o.color, o.time);
        ctrl.redraw();
      }
    },
    cclock(o) {
      if (ctrl.corresClock) {
        ctrl.data.correspondence.sente = o.sente;
        ctrl.data.correspondence.gote = o.gote;
        ctrl.corresClock.update(o.sente, o.gote);
        ctrl.redraw();
      }
    },
    crowd(o) {
      game.setOnGame(ctrl.data, 'sente', o['sente']);
      game.setOnGame(ctrl.data, 'gote', o['gote']);
      ctrl.redraw();
    },
    endData: ctrl.endWithData,
    rematchOffer(by: Color) {
      ctrl.data.player.offeringRematch = by === ctrl.data.player.color;
      if ((ctrl.data.opponent.offeringRematch = by === ctrl.data.opponent.color))
        notify(ctrl.noarg('yourOpponentWantsToPlayANewGameWithYou'));
      ctrl.redraw();
    },
    rematchTaken(nextId: string) {
      ctrl.data.game.rematch = nextId;
      if (!ctrl.data.player.spectator) ctrl.setLoading(true);
      else ctrl.redraw();
    },
    drawOffer(by) {
      ctrl.data.player.offeringDraw = by === ctrl.data.player.color;
      const fromOp = (ctrl.data.opponent.offeringDraw = by === ctrl.data.opponent.color);
      if (fromOp) notify(ctrl.noarg('yourOpponentOffersADraw'));
      ctrl.redraw();
    },
    pauseOffer(by) {
      if (by) {
        const fromOp = by === ctrl.data.opponent.color;
        if (fromOp) ctrl.data.opponent.offeringPause = true;
        else ctrl.data.player.offeringPause = true;
        if (fromOp && !ctrl.data.player.offeringPause) notify(ctrl.noarg('yourOpponentOffersAnAdjournment'));
      } else ctrl.data.player.offeringPause = ctrl.data.opponent.offeringPause = !!by;
      ctrl.redraw();
    },
    resumeOffer(by) {
      ctrl.data.player.offeringPause = ctrl.data.opponent.offeringPause = false;
      ctrl.data.player.offeringResume = by === ctrl.data.player.color;
      const fromOp = (ctrl.data.opponent.offeringResume = by === ctrl.data.opponent.color);
      if (fromOp) notify(ctrl.noarg('yourOpponentProposesResumption'));
      ctrl.redraw();
    },
    berserk(color: Color) {
      ctrl.setBerserk(color);
    },
    gone: ctrl.setGone,
    goneIn: ctrl.setGone,
    simulPlayerMove(gameId: string) {
      if (
        ctrl.opts.userId &&
        ctrl.data.simul &&
        ctrl.opts.userId == ctrl.data.simul.hostId &&
        gameId !== ctrl.data.game.id &&
        ctrl.moveOn.get() &&
        !isPlayerTurn(ctrl.data)
      ) {
        ctrl.setRedirecting();
        sound.move();
        li.hasToReload = true;
        location.href = '/' + gameId;
      }
    },
    simulEnd(simul: game.Simul) {
      li.loadCssPath('modal');
      $.modal(
        $(
          '<p>Simul complete!</p><br /><br />' +
            '<a class="button" href="/simul/' +
            simul.id +
            '">Back to ' +
            simul.name +
            ' simul</a>'
        )
      );
    },
    postGameStudy(studyId: string) {
      ctrl.data.game.postGameStudy = studyId;
      ctrl.postGameStudyOffer = true;
      ctrl.redraw();
    },
  };

  li.pubsub.on('ab.rep', n => send('rep', { n }));

  return {
    send,
    handlers,
    moreTime: throttle(300, () => send('moretime')),
    outoftime: backoff(500, 1.1, () => send('flag', ctrl.data.game.player)),
    berserk: throttle(200, () => send('berserk', null, { ackable: true })),
    sendLoading(typ: string, data?: any) {
      ctrl.setLoading(true);
      send(typ, data);
    },
    receive(typ: string, data: any): boolean {
      if (handlers[typ]) {
        handlers[typ](data);
        return true;
      }
      return false;
    },
    reload,
  };
}
