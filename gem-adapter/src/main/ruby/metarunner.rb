# The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
# https://github.com/artipie/artipie/blob/master/LICENSE.txt

# frozen_string_literal: true
require 'rubygems/indexer.rb'

# Regenerates the RubyGems index for the repository snapshot rooted at the
# parent of the gem's directory.
#
# SECURITY (2.2.9): the stored specs.4.8 / latest_specs.4.8 blobs are NEVER
# Marshal.load'ed here. They live in repository storage, so any principal
# holding repository WRITE can replace them, and Ruby's Marshal.load
# instantiates whatever classes the stream names -- the classic Ruby
# deserialization gadget surface, inside a full JRuby runtime. The previous
# implementation loaded them to merge the new gem into the existing index;
# the index is now rebuilt from the trusted .gem specs present in the
# snapshot instead (Gem::Package spec parsing is the same safe path RubyGems
# itself uses on install), and Gem::Indexer writes every index file,
# including the .gz variants, from scratch.
class MetaRunner

    def initialize(val)
        gemdir = File.dirname(val)
        tmpdir = File.expand_path("..", gemdir)
        Gem::Indexer.new(tmpdir, {build_modern:true}).generate_index
    end
end
