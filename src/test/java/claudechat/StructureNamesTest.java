package claudechat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** How structure pieces are named from their classes, and which points of a piece's box are sampled. */
class StructureNamesTest {
	/** Stands in for NetherFortressPieces: a class holding hand-coded piece classes. */
	static class FortressPieces {
		abstract static class Piece {
		}

		static class BridgeCrossing extends Piece {
		}

		static class MonsterThrone extends Piece {
		}

		/** A fortress's first piece is a crossing that the generator subclasses. */
		static class StartPiece extends BridgeCrossing {
		}
	}

	@Test
	void pieceClassIsNamedAfterItsStructureAndSnakeCaseName() {
		assertEquals("fortress/monster_throne", StructureFinder.className(FortressPieces.MonsterThrone.class));
	}

	@Test
	void startPieceTakesTheNameOfTheRoomItExtends() {
		assertEquals("fortress/bridge_crossing", StructureFinder.className(FortressPieces.StartPiece.class));
	}

	@Test
	void nameStopsAtAnAbstractParent() {
		assertEquals("fortress/bridge_crossing", StructureFinder.className(FortressPieces.BridgeCrossing.class));
	}

	@Test
	void classWithoutAnEnclosingClassHasOnlyItsOwnName() {
		assertEquals("array_list", StructureFinder.className(ArrayList.class));
	}

	@Test
	void samplesStepAcrossTheBoxAndAlwaysEndOnItsFarEdge() {
		int[] steps = StructureFinder.steps(-10, 12);

		assertEquals(-10, steps[0]);
		assertEquals(12, steps[steps.length - 1]);
		assertEquals(List.of(-10, -2, 6, 12), Arrays.stream(steps).boxed().distinct().toList());
	}

	@Test
	void flatBoxIsSampledAtItsOnlyCoordinate() {
		assertArrayEquals(new int[] {5}, Arrays.stream(StructureFinder.steps(5, 5)).distinct().toArray());
	}
}
