package com.visioncart.service.nlp;

import com.visioncart.api.dto.NlpParseRequest;
import com.visioncart.api.dto.NlpParseResult;

public interface NlpModelService {
    NlpParseResult parse(NlpParseRequest request);
}
