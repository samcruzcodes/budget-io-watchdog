import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))  # so tests can import features, metrics, experiment
