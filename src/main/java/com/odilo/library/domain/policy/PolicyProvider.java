package com.odilo.library.domain.policy;

import com.odilo.library.domain.model.Tier;

/** Source of the current business policies, replaceable by external configuration later. */
public interface PolicyProvider {

    FinePolicy finePolicy();

    TierPolicy tierPolicy(Tier tier);
}
