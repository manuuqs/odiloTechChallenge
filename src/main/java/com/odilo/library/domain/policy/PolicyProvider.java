package com.odilo.library.domain.policy;

/** Source of the current business policies, replaceable by external configuration later. */
public interface PolicyProvider {

    FinePolicy finePolicy();
}
