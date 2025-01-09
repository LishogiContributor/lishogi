#!/usr/bin/env ruby

require 'fileutils'
require 'base64'
include FileUtils

lila_dir = File.dirname(`pnpm root -w`.strip)
source_dir = lila_dir + '/public/piece/'
dest_dir = lila_dir + '/public/piece-css/'

bidirectional = []

themes = [
  ['Kyo_doubutsu', 'svg'],
  ['Kyo_joyful', 'png'],
  ['Kyo_orangain', 'svg'],
  ['Kyo_Kanji', 'svg'],
  ['Kyo_simple_kanji', 'svg'],
  ['Kyo_Intl', 'svg'],
  ['Kyo_international', 'svg'],
  ['Kyo_Logy_Games', 'svg'],
  ['Kyo_Ryoko_1Kanji', 'svg'],
]
types = {
  'svg' => 'svg+xml;base64,',
  'png' => 'png;base64,'
}
roles = ['FU', 'GI', 'GY', 'HI', 'KA', 'KE', 'KI', 'KY', 'OU','TO']
colors = ['sente', 'gote']

stanRoles = {
  'FU' => 'pawn',
  'GI' => 'silver',
  'GY' => 'tama',
  'HI' => 'rook',
  'KA' => 'bishop',
  'KE' => 'knight',
  'KI' => 'gold',
  'KY' => 'lance',
  'OU' => 'king',
  'TO' => 'tokin'
}

def classesWithOrientation(color, role, flipped)
  if flipped
    if color == 'sente'
      ".v-kyotoshogi .sg-wrap.orientation-gote piece.#{role}.gote,
      .v-kyotoshogi .hand-bottom piece.#{role}.gote,
      .spare-bottom.v-kyotoshogi piece.#{role}.gote"
    else
      ".v-kyotoshogi .sg-wrap.orientation-gote piece.#{role}.sente,
      .v-kyotoshogi .hand-top piece.#{role}.sente,
      .spare-top.v-kyotoshogi piece.#{role}.sente"
    end
  else
    if color == 'sente'
      ".v-kyotoshogi .sg-wrap.orientation-sente piece.#{role}.sente,
      .v-kyotoshogi .hand-bottom piece.#{role}.sente,
      .spare-bottom.v-kyotoshogi piece.#{role}.sente"
    else
      ".sg-wrap.orientation-sente piece.#{role}.gote,
      .v-kyotoshogi .hand-top piece.#{role}.gote,
      .spare-top.v-kyotoshogi piece.#{role}.gote"
    end
  end
end

def classes(color, role)
  if color == 'sente' # facing up
    if role == 'king'
      ".v-kyotoshogi .sg-wrap.orientation-gote piece.king.gote,
      .spare-bottom.v-kyotoshogi piece.king.gote"
    elsif role == 'tama'
      ".v-kyotoshogi piece.king.sente,
      .v-kyotoshogi .sg-wrap.orientation-sente piece.king.sente,
      .spare-bottom.v-kyotoshogi piece.king.sente"
    else
      ".v-kyotoshogi piece.#{role}.sente,
      .v-kyotoshogi .sg-wrap.orientation-sente piece.#{role}.sente,
      .v-kyotoshogi .sg-wrap.orientation-gote piece.#{role}.gote,
      .v-kyotoshogi .hand-bottom piece.#{role}.gote,
      .spare-bottom.v-kyotoshogi piece.#{role}"
    end
  else # facing down
    if role == 'king'
      ".v-kyotoshogi piece.king.gote,
      .v-kyotoshogi .sg-wrap.orientation-sente piece.king.gote,
      .spare-top.v-kyotoshogi piece.king.gote"
    elsif role == 'tama'
      ".v-kyotoshogi .sg-wrap.orientation-gote piece.king.sente,
      .spare-top.v-kyotoshogi piece.king.sente"
    else
      ".v-kyotoshogi piece.#{role}.gote,
      .v-kyotoshogi .sg-wrap.orientation-sente piece.#{role}.gote,
      .v-kyotoshogi .sg-wrap.orientation-gote piece.#{role}.sente,
      .v-kyotoshogi .hand-top piece.#{role},
      .spare-top.v-kyotoshogi piece.#{role}"
    end
  end
end

# inline SVG
themes.map { |theme|
  name = theme[0]
  ext = theme[1]
  classes = colors.map { |color|
    roles.map { |role|
      piece = (color == 'sente' ? '0' : '1') + role
      file = source_dir + name + '/' + piece + '.' + ext
      File.open(file, 'r') do|image_file|
        image = image_file.read
        base64 = Base64.strict_encode64(image)
        cls = classes(color, stanRoles[role])
        cls + ' {' +
          "background-image:url('data:image/" + types[ext] + base64 + "')!important;}"
      end
    }
  }.flatten
  if ext == 'png'
    classes.append(".v-kyotoshogi piece { will-change: transform !important; background-repeat: unset !important; }")
  end
  File.open(dest_dir + name + '.css', 'w') { |f| f.puts classes.join("\n") }
}
bidirectional.map { |theme|
  name = theme[0]
  ext = theme[1]
  classes = ['-1', ''].map { |up|
    colors.map { |color|
      roles.map { |role|
        piece = (color == 'sente' ? '0' : '1') + role + up
        file = source_dir + name + '/' + piece + '.' + ext
        File.open(file, 'r') do|image_file|
          image = image_file.read
          base64 = Base64.strict_encode64(image)
          cls = classesWithOrientation(color, stanRoles[role], up.length != 0)
          cls + ' {' +
            "background-image:url('data:image/" + types[ext] + base64 + "')!important;}"
        end
      }
    }
  }.flatten
  if ext == 'png'
    classes.append(".v-kyotoshogi piece { will-change: transform !important; background-repeat: unset !important; }")
  end
  File.open(dest_dir + name + '.css', 'w') { |f| f.puts classes.join("\n") }
}

# external SVG
themes.map { |theme|
  name = theme[0]
  ext = theme[1]
  classes = colors.map { |color|
    roles.map { |role|
      piece = (color == 'sente' ? '0' : '1') + role
      cls = classes(color, stanRoles[role]) 
      cls + ' {' +
        "background-image:url('/assets/piece/" + name + "/" + piece + "." + ext + "')!important;}"
    }
  }.flatten
  if ext == 'png'
    classes.append(".v-kyotoshogi piece { will-change: transform !important; background-repeat: unset !important; }")
  end
  File.open(dest_dir + name + '.external.css', 'w') { |f| f.puts classes.join("\n") }
}
bidirectional.map { |theme|
  name = theme[0]
  ext = theme[1]
  classes = ['-1', ''].map { |up|
    colors.map { |color|
      roles.map { |role|
        piece = (color == 'sente' ? '0' : '1') + role + up
        cls = classesWithOrientation(color, stanRoles[role], up.length != 0)
        cls + ' {' +
          "background-image:url('/assets/piece/" + name + "/" + piece + "." + ext + "')!important;}"     
      }
    }
  }.flatten
  if ext == 'png'
    classes.append(".v-kyotoshogi piece { will-change: transform !important; background-repeat: unset !important; }")
  end
  File.open(dest_dir + name + '.external.css', 'w') { |f| f.puts classes.join("\n") }
}