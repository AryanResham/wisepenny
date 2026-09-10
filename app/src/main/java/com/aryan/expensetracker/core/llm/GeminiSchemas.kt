package com.aryan.expensetracker.core.llm

// response schemas sent with every call so the model cannot answer in prose
object GeminiSchemas {

    const val EXTRACTION: String = """
    {
      "type": "object",
      "properties": {
        "isTransaction":   { "type": "boolean" },
        "amount":          { "type": "string", "nullable": true },
        "direction":       { "type": "string", "enum": ["DEBIT", "CREDIT"], "nullable": true },
        "merchant":        { "type": "string", "nullable": true },
        "referenceNumber": { "type": "string", "nullable": true },
        "accountTail":     { "type": "string", "nullable": true },
        "occurredOn":      { "type": "string", "nullable": true }
      },
      "required": ["isTransaction"]
    }
    """

    const val CATEGORIZATION: String = """
    {
      "type": "object",
      "properties": {
        "category": { "type": "string", "nullable": true }
      },
      "required": ["category"]
    }
    """
}
