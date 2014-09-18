CREATE TABLE users WITH DESCRIPTION 'A set of users'
ROW KEY FORMAT HASH PREFIXED(4)
WITH LOCALITY GROUP default
  WITH DESCRIPTION 'Main locality group' (
  MAXVERSIONS = INFINITY,
  TTL = FOREVER,
  INMEMORY = false,
  COMPRESSED WITH NONE,
  FAMILY info WITH DESCRIPTION 'Information about a user' (
    track_plays "string" WITH DESCRIPTION 'Tracks played by the user',
    next_song_rec "string" WITH DESCRIPTION 'Next song recommendation based on play history for a user'
  )
);

CREATE TABLE songs WITH DESCRIPTION 'Songs available for playing'
ROW KEY FORMAT HASH PREFIXED(4)
WITH LOCALITY GROUP default
  WITH DESCRIPTION 'Main locality group' (
  MAXVERSIONS = INFINITY,
  TTL = FOREVER,
  INMEMORY = false,
  COMPRESSED WITH NONE,
  FAMILY info WITH DESCRIPTION 'Information about a song' (
    metadata CLASS org.kiji.examples.music.SongMetadata WITH DESCRIPTION 'Song metadata',
    top_30_next_songs CLASS org.kiji.examples.music.NextSongProbability
      WITH DESCRIPTION 'Probabilities of a song being the next song to be played'
  )
);
