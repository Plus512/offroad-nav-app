package btools.router;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Package-private accessor helper bridging BRouter internal voice hints
 * to application routing models without reflection.
 */
public final class BRouterVoiceHintHelper {

    private BRouterVoiceHintHelper() {}

    public enum NormalizedManeuver {
        TURN_LEFT,
        TURN_RIGHT,
        U_TURN,
        STRAIGHT,
        ARRIVE
    }

    public static final class ParsedVoiceHint {
        private final NormalizedManeuver maneuver;
        private final double distanceToNext;
        private final String message;

        public ParsedVoiceHint(NormalizedManeuver maneuver, double distanceToNext, String message) {
            this.maneuver = maneuver;
            this.distanceToNext = distanceToNext;
            this.message = message;
        }

        public NormalizedManeuver getManeuver() {
            return maneuver;
        }

        public double getDistanceToNext() {
            return distanceToNext;
        }

        public String getMessage() {
            return message;
        }
    }

    public static List<ParsedVoiceHint> extractVoiceHints(OsmTrack track) {
        if (track == null || track.voiceHints == null || track.voiceHints.list == null) {
            return Collections.emptyList();
        }

        List<ParsedVoiceHint> result = new ArrayList<>();
        for (VoiceHint hint : track.voiceHints.list) {
            NormalizedManeuver maneuver;
            switch (hint.cmd) {
                case VoiceHint.TL:
                case VoiceHint.TSLL:
                case VoiceHint.TSHL:
                case VoiceHint.KL:
                case VoiceHint.BL:
                    maneuver = NormalizedManeuver.TURN_LEFT;
                    break;
                case VoiceHint.TR:
                case VoiceHint.TSLR:
                case VoiceHint.TSHR:
                case VoiceHint.KR:
                case VoiceHint.ER:
                    maneuver = NormalizedManeuver.TURN_RIGHT;
                    break;
                case VoiceHint.TU:
                case VoiceHint.TLU:
                case VoiceHint.TRU:
                    maneuver = NormalizedManeuver.U_TURN;
                    break;
                case VoiceHint.END:
                    maneuver = NormalizedManeuver.ARRIVE;
                    break;
                case VoiceHint.C:
                default:
                    maneuver = NormalizedManeuver.STRAIGHT;
                    break;
            }

            String msg = hint.getMessageString(1);
            if (msg == null || msg.trim().isEmpty()) {
                msg = hint.getCommandString(1);
            }
            result.add(new ParsedVoiceHint(maneuver, hint.distanceToNext, msg));
        }
        return result;
    }
}
